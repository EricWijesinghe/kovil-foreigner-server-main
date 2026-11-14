ALTER TABLE app_update_policies
    ADD COLUMN app VARCHAR(16) NOT NULL DEFAULT 'STUDENT' COMMENT 'STUDENT | PARTNER' AFTER id,
    DROP INDEX uk_app_update_policies_platform,
    ADD UNIQUE KEY uk_app_update_policies_app_platform (app, platform);

INSERT INTO app_update_policies(app,
                                platform,
                                min_supported_version,
                                latest_version,
                                store_url)
    VALUE (
           'PARTNER',
           'ANDROID',
           '1.0.0',
           '1.0.0',
           ''
    ),
    (
     'PARTNER',
     'IOS',
     '1.0.0',
     '1.0.0',
     ''
        );
