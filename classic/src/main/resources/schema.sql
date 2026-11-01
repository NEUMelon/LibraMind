CREATE TABLE IF NOT EXISTS student (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 student_no VARCHAR(32) NOT NULL UNIQUE,
 name VARCHAR(80) NOT NULL,
 college VARCHAR(120) NOT NULL,
 major VARCHAR(120) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 borrowed_count INT NOT NULL DEFAULT 0,
 CONSTRAINT chk_student_borrowed_count CHECK (borrowed_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS book (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 book_no VARCHAR(40) NOT NULL UNIQUE,
 title VARCHAR(200) NOT NULL,
 author VARCHAR(200) NOT NULL,
 edition VARCHAR(80) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
 cover_path VARCHAR(255) NULL,
 CONSTRAINT chk_book_status CHECK (status IN ('AVAILABLE','BORROWED','OFF_SHELF'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS loan (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 student_id BIGINT NOT NULL,
 book_id BIGINT NOT NULL,
 borrowed_at DATETIME NOT NULL,
 returned_at DATETIME NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 note VARCHAR(2000) NOT NULL DEFAULT '',
 active_book_id BIGINT GENERATED ALWAYS AS (CASE WHEN status='ACTIVE' THEN book_id ELSE NULL END) STORED,
 CONSTRAINT fk_loan_student FOREIGN KEY (student_id) REFERENCES student(id),
 CONSTRAINT fk_loan_book FOREIGN KEY (book_id) REFERENCES book(id),
 CONSTRAINT uq_active_book UNIQUE(active_book_id),
 CONSTRAINT chk_loan_status CHECK (status IN ('ACTIVE','RETURNED','CANCELLED')),
 CONSTRAINT chk_loan_dates CHECK (returned_at IS NULL OR returned_at >= borrowed_at),
 CONSTRAINT chk_loan_return CHECK ((status='ACTIVE' AND returned_at IS NULL) OR (status='RETURNED' AND returned_at IS NOT NULL) OR status='CANCELLED'),
 INDEX idx_loan_student(student_id,status), INDEX idx_loan_book(book_id,id), INDEX idx_loan_dates(borrowed_at,returned_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
