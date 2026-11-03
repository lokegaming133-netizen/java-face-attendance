CREATE TABLE IF NOT EXISTS students(
    id INTEGER AUTO_INCREMENT PRIMARY KEY,
    roll VARCHAR(50) UNIQUE NOT NULL,
    name VARCHAR(100) NOT NULL,
    gender VARCHAR(40) NOT NULL DEFAULT 'Not specified'
);
CREATE TABLE IF NOT EXISTS samples(
    id INTEGER AUTO_INCREMENT PRIMARY KEY,
    student INTEGER NOT NULL,
    image BLOB NOT NULL,
    FOREIGN KEY(student) REFERENCES students(id)
);
CREATE TABLE IF NOT EXISTS attendance(
    student INTEGER NOT NULL,
    session_name VARCHAR(100) NOT NULL,
    marked_at VARCHAR(40) NOT NULL,
    method VARCHAR(20) NOT NULL,
    PRIMARY KEY(student,session_name),
    FOREIGN KEY(student) REFERENCES students(id)
);

-- New, separate 128-D embeddings. Existing LBPH samples and attendance are untouched.
CREATE TABLE IF NOT EXISTS face_embeddings(
    id INTEGER AUTO_INCREMENT PRIMARY KEY,
    student INTEGER NOT NULL,
    embedding BLOB NOT NULL,
    FOREIGN KEY(student) REFERENCES students(id)
);
