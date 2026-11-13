DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM github_owner GROUP BY lower(login) HAVING count(*)>1) OR EXISTS(SELECT 1 FROM repository GROUP BY owner_id,lower(name) HAVING count(*)>1) THEN
 RAISE EXCEPTION 'Normalized owner/repository collision: reconcile stable GitHub identities before migration'; END IF;
END $$;
ALTER TABLE repository ADD COLUMN public_dashboard_enabled boolean NOT NULL DEFAULT false,
 ADD COLUMN public_traffic_enabled boolean NOT NULL DEFAULT false, ADD COLUMN public_referrers_enabled boolean NOT NULL DEFAULT false,
 ADD COLUMN public_paths_enabled boolean NOT NULL DEFAULT false, ADD COLUMN public_topics_enabled boolean NOT NULL DEFAULT false,
 ADD COLUMN publication_revision bigint NOT NULL DEFAULT 0, ADD COLUMN visibility_verified_at timestamptz;
ALTER TABLE search_query ADD COLUMN public_enabled boolean NOT NULL DEFAULT false;
CREATE UNIQUE INDEX uq_github_owner_login_ci ON github_owner(lower(login));
CREATE UNIQUE INDEX uq_repository_owner_name_ci ON repository(owner_id,lower(name));
CREATE INDEX repository_public_owner ON repository(owner_id,id) WHERE public_dashboard_enabled AND visibility='PUBLIC';
CREATE TABLE github_owner_alias(id bigserial PRIMARY KEY,owner_id bigint NOT NULL,login text NOT NULL,created_at timestamptz NOT NULL DEFAULT now());
CREATE UNIQUE INDEX owner_alias_login_ci ON github_owner_alias(lower(login));
CREATE INDEX owner_alias_target ON github_owner_alias(owner_id);
CREATE TABLE repository_path_alias(id bigserial PRIMARY KEY,repository_id bigint NOT NULL,owner_login text NOT NULL,repository_name text NOT NULL,created_at timestamptz NOT NULL DEFAULT now());
CREATE UNIQUE INDEX repository_alias_path_ci ON repository_path_alias(lower(owner_login),lower(repository_name));
CREATE INDEX repository_alias_target ON repository_path_alias(repository_id);
CREATE FUNCTION retain_repository_alias(target bigint,owner_name text,repo_name text) RETURNS void LANGUAGE plpgsql AS $$ BEGIN
 INSERT INTO repository_path_alias(repository_id,owner_login,repository_name) VALUES(target,owner_name,repo_name) ON CONFLICT DO NOTHING;
 IF NOT EXISTS(SELECT 1 FROM repository_path_alias WHERE lower(owner_login)=lower(owner_name) AND lower(repository_name)=lower(repo_name) AND repository_id=target) THEN RAISE EXCEPTION 'Repository alias conflict requires explicit reconciliation'; END IF;
END $$;
CREATE FUNCTION repository_publication_guard() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE owner_name text; BEGIN
 IF OLD.owner_id<>NEW.owner_id OR OLD.name<>NEW.name THEN
 SELECT login INTO owner_name FROM github_owner WHERE id=OLD.owner_id;
 PERFORM retain_repository_alias(OLD.id,owner_name,OLD.name);
 END IF;
 IF ROW(OLD.public_dashboard_enabled,OLD.public_traffic_enabled,OLD.public_referrers_enabled,OLD.public_paths_enabled,OLD.public_topics_enabled,OLD.visibility,OLD.visibility_verified_at,OLD.owner_id,OLD.name)
 IS DISTINCT FROM ROW(NEW.public_dashboard_enabled,NEW.public_traffic_enabled,NEW.public_referrers_enabled,NEW.public_paths_enabled,NEW.public_topics_enabled,NEW.visibility,NEW.visibility_verified_at,NEW.owner_id,NEW.name) THEN NEW.publication_revision=OLD.publication_revision+1; END IF;
 SELECT login INTO owner_name FROM github_owner WHERE id=NEW.owner_id;
 NEW.full_name=owner_name||'/'||NEW.name;
 RETURN NEW; END $$;
CREATE TRIGGER repository_publication_guard BEFORE UPDATE ON repository FOR EACH ROW EXECUTE FUNCTION repository_publication_guard();
CREATE FUNCTION retain_owner_alias() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE repo record; BEGIN
 IF OLD.login<>NEW.login THEN
 INSERT INTO github_owner_alias(owner_id,login) VALUES(OLD.id,OLD.login) ON CONFLICT DO NOTHING;
 IF NOT EXISTS(SELECT 1 FROM github_owner_alias WHERE lower(login)=lower(OLD.login) AND owner_id=OLD.id) THEN RAISE EXCEPTION 'Owner alias conflict requires explicit reconciliation'; END IF;
 FOR repo IN SELECT id,name FROM repository WHERE owner_id=OLD.id ORDER BY id LOOP PERFORM retain_repository_alias(repo.id,OLD.login,repo.name); END LOOP;
 UPDATE repository SET full_name=NEW.login||'/'||name,publication_revision=publication_revision+1 WHERE owner_id=OLD.id;
 END IF; RETURN NEW; END $$;
CREATE TRIGGER retain_owner_alias AFTER UPDATE OF login ON github_owner FOR EACH ROW EXECUTE FUNCTION retain_owner_alias();
CREATE FUNCTION query_publication_revision() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF OLD.public_enabled IS DISTINCT FROM NEW.public_enabled THEN UPDATE repository SET publication_revision=publication_revision+1 WHERE id=NEW.repository_id; END IF; RETURN NEW; END $$;
CREATE TRIGGER query_publication_revision AFTER UPDATE OF public_enabled ON search_query FOR EACH ROW EXECUTE FUNCTION query_publication_revision();
