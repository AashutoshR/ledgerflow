CREATE TABLE proposals (
 id uuid PRIMARY KEY, owner_id text NOT NULL, client_id uuid NOT NULL,
 recipient_email varchar(254) NOT NULL, payload jsonb NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','SENT','ACCEPTED','DECLINED')),
 project_id uuid UNIQUE, created_at timestamptz NOT NULL DEFAULT now(),
 decided_by text, decided_at timestamptz,
 FOREIGN KEY(client_id,owner_id) REFERENCES clients(id,owner_id),
 FOREIGN KEY(project_id,owner_id) REFERENCES projects(id,owner_id),
 CHECK ((status='ACCEPTED') = (project_id IS NOT NULL)));
CREATE INDEX proposals_owner ON proposals(owner_id,created_at DESC);
CREATE INDEX proposals_recipient ON proposals(recipient_email,created_at DESC);
CREATE TABLE proposal_comments (
 id uuid PRIMARY KEY, proposal_id uuid NOT NULL REFERENCES proposals(id), author_id text NOT NULL,
 author_role varchar(10) NOT NULL CHECK(author_role IN ('STUDIO','CLIENT')),
 body varchar(1000) NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX proposal_comments_proposal ON proposal_comments(proposal_id,created_at);

CREATE TABLE proposal_deliverables (
 proposal_id uuid NOT NULL REFERENCES proposals(id),
 task_id uuid NOT NULL REFERENCES tasks(id),
 PRIMARY KEY(proposal_id,task_id));
