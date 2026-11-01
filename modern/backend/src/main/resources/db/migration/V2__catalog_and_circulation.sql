CREATE TABLE work (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    display_title VARCHAR(400) NOT NULL,
    display_creators VARCHAR(500) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE book_edition (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    work_id BIGINT NOT NULL,
    title VARCHAR(400) NOT NULL,
    subtitle VARCHAR(400) NOT NULL DEFAULT '',
    authors VARCHAR(500) NOT NULL,
    translators VARCHAR(500) NOT NULL DEFAULT '',
    isbn13 VARCHAR(13) NULL,
    publisher VARCHAR(200) NOT NULL DEFAULT '',
    publication_date VARCHAR(80) NOT NULL DEFAULT '',
    edition_statement VARCHAR(100) NOT NULL DEFAULT '',
    volume VARCHAR(80) NOT NULL DEFAULT '',
    language VARCHAR(32) NOT NULL DEFAULT 'zh',
    classification_no VARCHAR(80) NOT NULL DEFAULT '',
    category VARCHAR(100) NOT NULL DEFAULT '',
    description TEXT NOT NULL,
    cover_path VARCHAR(100) NULL,
    source_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    source_record_id VARCHAR(200) NOT NULL DEFAULT '',
    source_url VARCHAR(1000) NOT NULL DEFAULT '',
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_edition_isbn (isbn13),
    CONSTRAINT fk_edition_work FOREIGN KEY (work_id) REFERENCES work(id),
    FULLTEXT KEY ft_edition_search (title,authors,publisher,category,description) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE book_copy (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    accession_no VARCHAR(64) NOT NULL,
    edition_id BIGINT NOT NULL,
    call_number VARCHAR(100) NOT NULL DEFAULT '',
    location VARCHAR(200) NOT NULL DEFAULT '',
    circulation_status VARCHAR(24) NOT NULL DEFAULT 'AVAILABLE',
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    acquired_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_copy_accession (accession_no),
    KEY ix_copy_edition_status (edition_id,circulation_status),
    CONSTRAINT fk_copy_edition FOREIGN KEY (edition_id) REFERENCES book_edition(id),
    CONSTRAINT ck_copy_status CHECK (circulation_status IN ('AVAILABLE','BORROWED','MAINTENANCE','OFF_SHELF'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE loan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_id BIGINT NOT NULL,
    copy_id BIGINT NOT NULL,
    borrowed_at TIMESTAMP(6) NOT NULL,
    due_at TIMESTAMP(6) NOT NULL,
    returned_at TIMESTAMP(6) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    renew_count INT NOT NULL DEFAULT 0,
    operator_id BIGINT NOT NULL,
    note VARCHAR(1000) NOT NULL DEFAULT '',
    cancelled_at TIMESTAMP(6) NULL,
    cancellation_reason VARCHAR(500) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    active_copy_id BIGINT GENERATED ALWAYS AS (IF(status='ACTIVE',copy_id,NULL)) STORED,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_active_copy (active_copy_id),
    KEY ix_loan_student_status (student_id,status),
    KEY ix_loan_copy_time (copy_id,borrowed_at),
    CONSTRAINT fk_loan_student FOREIGN KEY (student_id) REFERENCES student(id),
    CONSTRAINT fk_loan_copy FOREIGN KEY (copy_id) REFERENCES book_copy(id),
    CONSTRAINT fk_loan_operator FOREIGN KEY (operator_id) REFERENCES user_account(id),
    CONSTRAINT ck_loan_status CHECK (status IN ('ACTIVE','RETURNED','CANCELLED')),
    CONSTRAINT ck_loan_dates CHECK (due_at >= borrowed_at AND (returned_at IS NULL OR returned_at >= borrowed_at)),
    CONSTRAINT ck_loan_return_state CHECK ((status='ACTIVE' AND returned_at IS NULL) OR status<>'ACTIVE'),
    CONSTRAINT ck_loan_renew_count CHECK (renew_count>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operator_id BIGINT NOT NULL,
    operation_type VARCHAR(64) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id BIGINT NOT NULL,
    before_snapshot JSON NULL,
    after_snapshot JSON NULL,
    reason VARCHAR(500) NOT NULL DEFAULT '',
    request_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY ix_audit_target (target_type,target_id),
    CONSTRAINT fk_audit_operator FOREIGN KEY (operator_id) REFERENCES user_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE idempotency_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    idem_key VARCHAR(64) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_json JSON NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_idempotency_user_key (user_id,idem_key),
    CONSTRAINT fk_idempotency_user FOREIGN KEY (user_id) REFERENCES user_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
