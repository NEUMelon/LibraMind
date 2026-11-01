CREATE TABLE knowledge_document (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    edition_id BIGINT NULL,
    document_type VARCHAR(32) NOT NULL,
    title VARCHAR(400) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    source VARCHAR(500) NOT NULL DEFAULT '',
    source_url VARCHAR(1000) NOT NULL DEFAULT '',
    visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC',
    version INT NOT NULL DEFAULT 1,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_knowledge_edition FOREIGN KEY (edition_id) REFERENCES book_edition(id),
    CONSTRAINT ck_knowledge_visibility CHECK (visibility IN ('PUBLIC','ADMIN')),
    KEY ix_knowledge_edition (edition_id,document_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE knowledge_chunk (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_document_chunk (document_id,chunk_index),
    CONSTRAINT fk_chunk_document FOREIGN KEY (document_id) REFERENCES knowledge_document(id),
    FULLTEXT KEY ft_chunk_content (content) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE chunk_embedding (
    chunk_id BIGINT NOT NULL,
    model_key VARCHAR(120) NOT NULL,
    dimension INT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    vector MEDIUMBLOB NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (chunk_id,model_key),
    CONSTRAINT fk_embedding_chunk FOREIGN KEY (chunk_id) REFERENCES knowledge_chunk(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE index_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    chunk_id BIGINT NOT NULL,
    operation VARCHAR(16) NOT NULL DEFAULT 'UPSERT',
    content_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    claim_token VARCHAR(36) NULL,
    lease_until TIMESTAMP(6) NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_error VARCHAR(200) NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_index_task_chunk (chunk_id),
    KEY ix_index_task_poll (status,next_retry_at),
    CONSTRAINT fk_task_chunk FOREIGN KEY (chunk_id) REFERENCES knowledge_chunk(id),
    CONSTRAINT ck_task_status CHECK (status IN ('PENDING','PROCESSING','DONE','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_conversation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL DEFAULT '新会话',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_conversation_user FOREIGN KEY (user_id) REFERENCES user_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    model VARCHAR(120) NOT NULL DEFAULT '',
    token_usage INT NOT NULL DEFAULT 0,
    latency_ms BIGINT NOT NULL DEFAULT 0,
    structured_result_refs JSON NULL,
    citations JSON NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversation(id),
    KEY ix_message_conversation (conversation_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_tool_execution (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    tool_name VARCHAR(100) NOT NULL,
    input_summary VARCHAR(500) NOT NULL DEFAULT '',
    result_summary VARCHAR(1000) NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL,
    latency_ms BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_tool_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversation(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE pending_action (
    id CHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    conversation_id BIGINT NULL,
    action_type VARCHAR(16) NOT NULL,
    target_id BIGINT NOT NULL,
    payload_snapshot JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    expires_at TIMESTAMP(6) NOT NULL,
    result_json JSON NULL,
    failure_code VARCHAR(80) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_action_user FOREIGN KEY (user_id) REFERENCES user_account(id),
    CONSTRAINT fk_action_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversation(id),
    CONSTRAINT ck_action_status CHECK (status IN ('PENDING','SUCCEEDED','FAILED','CANCELLED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE recognition_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL,
    extracted_fields JSON NULL,
    candidates JSON NULL,
    user_choice BIGINT NULL,
    outcome VARCHAR(32) NOT NULL,
    latency_ms BIGINT NOT NULL,
    model VARCHAR(120) NOT NULL DEFAULT '',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_recognition_user FOREIGN KEY (user_id) REFERENCES user_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_budget_day (
    budget_date DATE PRIMARY KEY,
    reserved_usd DECIMAL(12,6) NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_request (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NULL,
    request_type VARCHAR(32) NOT NULL,
    model VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    input_tokens INT NOT NULL DEFAULT 0,
    output_tokens INT NOT NULL DEFAULT 0,
    reserved_usd DECIMAL(12,6) NOT NULL,
    estimated_usd DECIMAL(12,6) NULL,
    latency_ms BIGINT NOT NULL DEFAULT 0,
    error_code VARCHAR(100) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
