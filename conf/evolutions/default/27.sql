-- !Ups

DELETE FROM USERS WHERE role = 'ToActivate';
ALTER TABLE USERS
    ALTER COLUMN email SET NOT NULL,
    ALTER COLUMN firstname SET NOT NULL,
    ALTER COLUMN lastname SET NOT NULL;

-- !Downs

ALTER TABLE USERS
    ALTER COLUMN email DROP NOT NULL,
    ALTER COLUMN firstname DROP NOT NULL,
    ALTER COLUMN lastname DROP NOT NULL;
