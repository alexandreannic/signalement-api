-- !Ups

CREATE TABLE REPORT_DATA (
    REPORT_ID UUID NOT NULL PRIMARY KEY REFERENCES REPORTS(ID),
    READ_DELAY BIGINT,
    RESPONSE_DELAY BIGINT
);

-- !Downs

DROP TABLE REPORT_DATA;