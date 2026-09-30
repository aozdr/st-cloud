SET NAMES utf8mb4;
DROP TABLE IF EXISTS team_member;
DROP TABLE IF EXISTS team_invite;
DROP TABLE IF EXISTS sys_user;
CREATE TABLE team_member (
    id BIGINT PRIMARY KEY,
    role TINYINT NOT NULL DEFAULT 2
);
CREATE TABLE team_invite (
    id BIGINT PRIMARY KEY,
    role TINYINT NOT NULL DEFAULT 2
);
CREATE TABLE sys_user (
    id BIGINT PRIMARY KEY,
    username VARCHAR(64) NOT NULL
);
INSERT INTO team_member (id, role) VALUES (1, 0), (2, 1), (3, 2), (4, 9);
INSERT INTO team_invite (id, role) VALUES (1, 0), (2, 1), (3, 2), (4, 9);
INSERT INTO sys_user (id, username) VALUES (1, 'active'), (2, 'disabled');
