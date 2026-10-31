CREATE TABLE clients (
 id uuid PRIMARY KEY, owner_id text NOT NULL, name varchar(100) NOT NULL,
 email varchar(254) NOT NULL, company varchar(100) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(id,owner_id));
CREATE INDEX clients_owner ON clients(owner_id,created_at DESC);
CREATE TABLE projects (
 id uuid PRIMARY KEY, owner_id text NOT NULL, client_id uuid, name varchar(100) NOT NULL,
 description varchar(1000) NOT NULL, due_date date NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(id,owner_id),
 FOREIGN KEY(client_id,owner_id) REFERENCES clients(id,owner_id));
CREATE INDEX projects_owner ON projects(owner_id,created_at DESC);
CREATE TABLE tasks (
 id uuid PRIMARY KEY, owner_id text NOT NULL, project_id uuid NOT NULL,
 title varchar(160) NOT NULL, status varchar(20) NOT NULL DEFAULT 'TODO' CHECK(status IN ('TODO','DOING','DONE')),
 version integer NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(project_id,owner_id) REFERENCES projects(id,owner_id));
CREATE INDEX tasks_owner ON tasks(owner_id,created_at DESC);
CREATE TABLE activity (
 id uuid PRIMARY KEY, owner_id text NOT NULL, message varchar(300) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX activity_owner ON activity(owner_id,created_at DESC);
