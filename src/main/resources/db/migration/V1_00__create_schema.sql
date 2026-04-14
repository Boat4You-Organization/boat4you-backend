create table if not exists users
(
    id bigserial not null
        constraint pk_users_id primary key,
    name varchar(255) not null,
    surname varchar(255) not null,
    password varchar(255) not null,
    email varchar(255) not null,
    language varchar(10),
    currency varchar(3),

    login_attempts integer not null,
    password_reset_code varchar(255),
    last_unsuccessful_login timestamp,

    creator_id bigint,
    modifier_id bigint,
    created timestamp not null,
    modified timestamp,
    entity_status varchar(31) not null
);

CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);

create table if not exists roles
(
    id bigserial not null
        constraint pk_roles_id primary key,
    name varchar(255) not null
        constraint uk_roles_name unique,

    creator_id bigint,
    modifier_id bigint,
    created timestamp not null,
    modified timestamp,
    entity_status varchar(31) not null
);

CREATE INDEX IF NOT EXISTS idx_roles_name ON roles(name);

create table if not exists role_assignments
(
    id bigserial not null
        constraint pk_role_assignments_id primary key,
    user_id bigint not null,
    constraint fk_role_assignment_user foreign key(user_id)
        references users(id),
    role_id bigint not null,
    constraint fk_role_assignment_role foreign key(role_id)
        references roles(id),

    creator_id bigint,
    modifier_id bigint,
    created timestamp not null,
    modified timestamp,
    entity_status varchar(31) not null
);

CREATE UNIQUE INDEX IF NOT EXISTS uidx_role_assignments_user_role ON role_assignments(user_id, role_id);

create table if not exists tokens
(
    id bigserial not null
        constraint pk_tokens_id primary key,
    value varchar(1024) not null,
    is_revoked boolean not null,
    is_expired boolean not null,

    user_id bigint not null,
    constraint fk_tokens_user foreign key(user_id)
        references users(id),

    creator_id bigint,
    modifier_id bigint,
    created timestamp not null,
    modified timestamp,
    entity_status varchar(31) not null
);

CREATE UNIQUE INDEX IF NOT EXISTS uidx_tokens_value ON tokens(value);


CREATE POLICY app_user ON public.users
	AS PERMISSIVE
	FOR ALL
	TO boat4you_app;

CREATE POLICY app_user ON public.roles
	AS PERMISSIVE
	FOR ALL
	TO boat4you_app;

CREATE POLICY app_user ON public.role_assignments
	AS PERMISSIVE
	FOR ALL
	TO boat4you_app;

CREATE POLICY app_user ON public.tokens
	AS PERMISSIVE
	FOR ALL
	TO boat4you_app;