package local.attendance;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.objdetect.FaceDetectorYN;
import org.opencv.objdetect.FaceRecognizerSF;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class FaceService {
    static {
        // Use the already-present Bytedeco OpenCV native binaries; no Python backend.
        Loader.load(opencv_java.class);
    }

    private static final int EMBEDDING_LENGTH = 128;
    private static final String YUNET = "models/face_detection_yunet_2023mar.onnx";
    private static final String SFACE = "models/face_recognition_sface_2021dec.onnx";

    private final JdbcTemplate db;
    private final FaceDetectorYN detector;
    private final FaceRecognizerSF recognizer;
    private final double threshold;
    private List<Embedding> enrolled;

    private record Embedding(int studentId, float[] vector) { }
    public record FaceBox(int x, int y, int width, int height, Integer studentId) { }
    public record ScanResult(int width, int height, List<FaceBox> faces) { }

    public FaceService(JdbcTemplate db, @Value("${face.sface.cosine-threshold:0.40}") double threshold) {
        this.db = db;
        if (threshold < 0.30 || threshold > 0.80) {
            throw new IllegalArgumentException("face.sface.cosine-threshold must be between 0.30 and 0.80");
        }
        this.threshold = threshold;
        if (!Files.isRegularFile(Path.of(YUNET)) || !Files.isRegularFile(Path.of(SFACE))) {
            throw new IllegalStateException("SFace/YuNet model missing. Stop server and run setup.ps1 in project folder.");
        }
        try {
            detector = FaceDetectorYN.create(YUNET, "", new Size(640, 480), 0.72f, 0.30f, 1000);
            recognizer = FaceRecognizerSF.create(SFACE, "");
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load YuNet/SFace models. Rerun setup.ps1 and check downloads.", e);
        }
    }

    private Mat decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > 2_000_000) {
            throw new IllegalArgumentException("Image missing or too large (2 MB max)");
        }
        MatOfByte encoded = new MatOfByte(bytes);
        Mat image;
        try {
            image = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR);
        } finally {
            encoded.release();
        }
        if (image.empty()) {
            image.release();
            throw new IllegalArgumentException("Invalid webcam image");
        }
        if (image.cols() > 1500 || image.rows() > 1500) {
            image.release();
            throw new IllegalArgumentException("Maximum frame dimensions are 1500x1500");
        }
        return image;
    }

    private Mat detect(Mat image) {
        detector.setInputSize(new Size(image.cols(), image.rows()));
        Mat faces = new Mat();
        detector.detect(image, faces);
        return faces;
    }

    private float[] embedding(Mat image, Mat faces, int index) {
        Mat face = faces.row(index);
        Mat aligned = new Mat();
        Mat features = new Mat();
        try {
            recognizer.alignCrop(image, face, aligned);
            recognizer.feature(aligned, features);
            int count = (int) (features.total() * features.channels());
            if (count != EMBEDDING_LENGTH) {
                throw new IllegalStateException("Unexpected SFace feature size: " + count);
            }
            float[] data = new float[count];
            features.get(0, 0, data);
            double normSquared = 0;
            for (float f : data) normSquared += (double) f * f;
            double norm = Math.sqrt(normSquared);
            if (norm < 1.0e-10) throw new IllegalStateException("Empty face embedding");
            for (int i = 0; i < data.length; i++) data[i] /= (float) norm;
            return data;
        } finally {
            features.release();
            aligned.release();
            face.release();
        }
    }

    private static byte[] encode(float[] floats) {
        ByteBuffer buffer = ByteBuffer.allocate(EMBEDDING_LENGTH * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : floats) buffer.putFloat(f);
        return buffer.array();
    }

    private static float[] decodeFeature(byte[] blob) {
        if (blob == null || blob.length != EMBEDDING_LENGTH * Float.BYTES) {
            throw new IllegalStateException("Invalid saved face embedding format");
        }
        ByteBuffer buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        float[] floats = new float[EMBEDDING_LENGTH];
        for (int i = 0; i < floats.length; i++) floats[i] = buffer.getFloat();
        return floats;
    }

    // Save new SFace embeddings separately. Previously captured LBPH samples stay untouched.
    public synchronized byte[] enroll(byte[] bytes) {
        Mat image = decode(bytes);
        try {
            Mat found = detect(image);
            try {
                if (found.rows() != 1) {
                    throw new IllegalArgumentException("Exactly one clear face required; detected " + found.rows());
                }
                return encode(embedding(image, found, 0));
            } finally {
                found.release();
            }
        } finally {
            image.release();
        }
    }

    public synchronized void invalidateModel() { enrolled = null; }

    private List<Embedding> saved() {
        if (enrolled == null) {
            enrolled = db.query("SELECT student, embedding FROM face_embeddings", (rs, row) ->
                new Embedding(rs.getInt("student"), decodeFeature(rs.getBytes("embedding"))));
        }
        return enrolled;
    }

    private static double cosine(float[] a, float[] b) {
        double value = 0;
        for (int i = 0; i < EMBEDDING_LENGTH; i++) value += (double) a[i] * b[i];
        return value;
    }

    private int match(float[] face) {
        List<Embedding> data = saved();
        if (data.isEmpty()) return -1;
        Map<Integer, Double> bestByStudent = new HashMap<>();
        for (Embedding sample : data) {
            bestByStudent.merge(sample.studentId(), cosine(face, sample.vector()), Math::max);
        }
        int winner = -1;
        double first = -1.0, runnerUp = -1.0;
        for (var entry : bestByStudent.entrySet()) {
            double score = entry.getValue();
            if (score > first) {
                runnerUp = first;
                first = score;
                winner = entry.getKey();
            } else if (score > runnerUp) {
                runnerUp = score;
            }
        }
        // Default 0.40 is an UNCALIBRATED starting point (OpenCV example uses 0.363).
        // The 0.05 runner-up gap reduces confusing two enrolled identities.
        return first >= threshold && (runnerUp < 0 || first - runnerUp >= 0.05) ? winner : -1;
    }

    public synchronized int identify(byte[] bytes) {
        if (saved().isEmpty()) {
            throw new IllegalArgumentException("New face model needs fresh enrollment. Select student and capture 5-10 new face samples.");
        }
        Mat image = decode(bytes);
        try {
            Mat faces = detect(image);
            try {
                if (faces.rows() != 1) {
                    throw new IllegalArgumentException("Exactly one clear face required; detected " + faces.rows());
                }
                return match(embedding(image, faces, 0));
            } finally {
                faces.release();
            }
        } finally {
            image.release();
        }
    }

    public synchronized ScanResult scan(byte[] bytes) {
        Mat image = decode(bytes);
        try {
            Mat faces = detect(image);
            try {
                List<FaceBox> boxes = new ArrayList<>();
                int detected = faces.rows();
                for (int i = 0; i < Math.min(detected, 5); i++) {
                    float[] f = new float[15];
                    faces.get(i, 0, f);
                    Integer person = null;
                    if (detected == 1 && !saved().isEmpty()) {
                        int candidate = match(embedding(image, faces, i));
                        if (candidate >= 0) person = candidate;
                    }
                    boxes.add(new FaceBox(Math.max(0, Math.round(f[0])), Math.max(0, Math.round(f[1])),
                            Math.max(1, Math.round(f[2])), Math.max(1, Math.round(f[3])), person));
                }
                return new ScanResult(image.cols(), image.rows(), boxes);
            } finally {
                faces.release();
            }
        } finally {
            image.release();
        }
    }
}
