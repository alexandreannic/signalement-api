# --- !Ups

UPDATE SIGNALEMENT SET DETAILS = array[]::varchar[] WHERE DETAILS = array[null]::varchar[];
UPDATE SIGNALEMENT SET DETAILS = array[]::varchar[] WHERE DETAILS = array['Précision : ']::varchar[];

UPDATE SIGNALEMENT SET DETAILS = array_append(DETAILS, concat('Description : ', DESCRIPTION)::varchar) WHERE DESCRIPTION IS NOT NULL and DESCRIPTION <> '';
UPDATE SIGNALEMENT SET DETAILS = array_append(DETAILS, concat('Date du constat : ', to_char(DATE_CONSTAT, 'DD/MM/YYYY'))::varchar) WHERE DATE_CONSTAT IS NOT NULL;
UPDATE SIGNALEMENT SET DETAILS = array_append(DETAILS, concat('Heure du constat : de ', HEURE_CONSTAT, 'h à ', HEURE_CONSTAT + 1, 'h')::varchar) WHERE HEURE_CONSTAT IS NOT NULL;

ALTER TABLE SIGNALEMENT DROP COLUMN DESCRIPTION;
ALTER TABLE SIGNALEMENT DROP COLUMN DATE_CONSTAT;
ALTER TABLE SIGNALEMENT DROP COLUMN HEURE_CONSTAT;


# --- !Downs

ALTER TABLE SIGNALEMENT ADD COLUMN HEURE_CONSTAT NUMERIC;
ALTER TABLE SIGNALEMENT ADD COLUMN DATE_CONSTAT DATE;
ALTER TABLE SIGNALEMENT ADD COLUMN DESCRIPTION VARCHAR;
