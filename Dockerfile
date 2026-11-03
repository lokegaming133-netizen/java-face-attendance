# Render Free web service: Docker deployment of Java 17 Spring Boot + OpenCV.
FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-jammy
ENV DEBIAN_FRONTEND=noninteractive
# OpenCV's native Linux dependencies and model download utilities.
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl libstdc++6 libglib2.0-0 libgtk2.0-0 libgl1 libsm6 libxext6 libxrender1 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
RUN mkdir -p models \
  && curl -fLSs --retry 3 --retry-delay 3 -o models/face_detection_yunet_2023mar.onnx \
    'https://huggingface.co/opencv/opencv_zoo/resolve/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx' \
  && echo '8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4  models/face_detection_yunet_2023mar.onnx' | sha256sum -c - \
  && curl -fLSs --retry 3 --retry-delay 3 -o models/face_recognition_sface_2021dec.onnx \
    'https://huggingface.co/opencv/opencv_zoo/resolve/main/models/face_recognition_sface/face_recognition_sface_2021dec.onnx' \
  && echo '0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79  models/face_recognition_sface_2021dec.onnx' | sha256sum -c -
COPY --from=build /build/target/attendance-1.0.0.jar ./app.jar
ENV SPRING_PROFILES_ACTIVE=cloud
EXPOSE 10000
ENTRYPOINT ["java","-XX:MaxRAMPercentage=48.0","-XX:ActiveProcessorCount=1","-Djava.awt.headless=true","-jar","/app/app.jar"]
