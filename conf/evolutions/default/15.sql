# --- !Ups

CREATE TABLE SUBSCRIPTIONS
(
    ID UUID PRIMARY KEY,
    USER_ID UUID,
    CATEGORY VARCHAR NOT NULL,
    VALUES VARCHAR[] NOT NULL
);

ALTER TABLE SUBSCRIPTIONS ADD CONSTRAINT FK_SUBSCRIPTION_USER FOREIGN KEY (USER_ID) REFERENCES USERS(ID);

# --- !Downs

DROP TABLE SUBSCRIPTIONS
