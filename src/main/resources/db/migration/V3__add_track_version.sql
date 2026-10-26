ALTER TABLE `tracks`
  ADD COLUMN `version` BIGINT NOT NULL DEFAULT 0 AFTER `updated_by`;
