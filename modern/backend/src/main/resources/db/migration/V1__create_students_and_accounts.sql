CREATE TABLE student (
    id BIGINT NOT NULL AUTO_INCREMENT,
    student_no VARCHAR(32) NOT NULL,
    name VARCHAR(80) NOT NULL,
    college VARCHAR(120) NOT NULL,
    major VARCHAR(120) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_student_number UNIQUE (student_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_account (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(16) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    student_id BIGINT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_account_username UNIQUE (username),
    CONSTRAINT uk_account_student UNIQUE (student_id),
    CONSTRAINT fk_account_student FOREIGN KEY (student_id) REFERENCES student (id),
    CONSTRAINT ck_account_role CHECK (role IN ('ADMIN', 'STUDENT')),
    CONSTRAINT ck_student_account_link CHECK (role <> 'STUDENT' OR student_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
