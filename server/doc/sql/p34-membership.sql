-- Apply after P33. No credentials are seeded. Selling stays disabled.
CREATE TABLE IF NOT EXISTS membership_config (
 id INT PRIMARY KEY, version BIGINT NOT NULL, config_json LONGTEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS membership_payment_profile (
 id VARCHAR(36) PRIMARY KEY, channel VARCHAR(8) NOT NULL, config_json LONGTEXT NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS membership_payment_active (
 channel VARCHAR(8) PRIMARY KEY, profile_id VARCHAR(36) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS membership_order (
 id VARCHAR(40) PRIMARY KEY, user_id BIGINT NOT NULL, request_key VARCHAR(80) NOT NULL,
 plan_id VARCHAR(28) NOT NULL, plan_json LONGTEXT NOT NULL, agreement_text LONGTEXT NOT NULL,
 profile_id VARCHAR(36) NOT NULL, channel VARCHAR(8) NOT NULL, amount_fen BIGINT NOT NULL,
 status VARCHAR(12) NOT NULL, trade_no VARCHAR(80) NULL, error_message VARCHAR(300) NOT NULL DEFAULT '',
 created_at DATETIME(3) NOT NULL, expires_at DATETIME(3) NOT NULL, paid_at DATETIME(3) NULL,
 granted_at DATETIME(3) NULL, grant_start DATETIME(3) NULL, grant_end DATETIME(3) NULL,
 next_check_at DATETIME(3) NOT NULL, attempts INT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_membership_request(user_id,request_key), UNIQUE KEY uk_membership_trade(trade_no),
 KEY idx_membership_reconcile(status,next_check_at), KEY idx_membership_user(user_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS user_membership (
 user_id BIGINT PRIMARY KEY, plan_id VARCHAR(28) NOT NULL, expires_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS membership_frame_grant (
 user_id BIGINT NOT NULL, frame_id VARCHAR(32) NOT NULL, expires_at DATETIME(3) NOT NULL,
 PRIMARY KEY(user_id,frame_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
