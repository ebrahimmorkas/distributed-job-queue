-- Which worker finished the job: useful for debugging and to see work spread across instances.
ALTER TABLE jobs ADD COLUMN completed_by VARCHAR(100);
