-- Separate the driver's free-text failure comment from the admin failure-reason label.
-- Historically both were flattened into delivery.fail_reason as "LABEL — comment", which is
-- ambiguous when the admin label itself contains " — " (FailureReason.label is free admin text).
-- Store the driver's words in their own column so the admin motif and the driver comment can be
-- surfaced separately. fail_reason is left untouched — its collapsed form is still consumed by ERP
-- sync, analytics, public tracking and the PDF route reports.
ALTER TABLE deliveries ADD COLUMN failure_comment TEXT;
