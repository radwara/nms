-- Seeded directory. Addresses follow the simulated-outcome convention in docs/architecture.md:
-- email <send>.<callback>@sim.test, SMS +1555010SSCC (SS = send code, CC = callback code).
-- Synthetic data only. A missing channel_preference row means enabled.

insert into recipient (id, email, phone) values
    -- Directory shapes
    ('user-ok',          'ok.delivered@sim.test',        '+15550100000'),
    ('user-email-only',  'ok.delivered@sim.test',        null),
    ('user-sms-optout',  'ok.delivered@sim.test',        '+15550100000'),
    -- One recipient per send outcome, both channels, callback delivered
    ('user-transient',   'transient.delivered@sim.test', '+15550100100'),
    ('user-timeout',     'timeout.delivered@sim.test',   '+15550100200'),
    ('user-ratelimit',   'ratelimit.delivered@sim.test', '+15550100300'),
    ('user-reject',      'reject.delivered@sim.test',    '+15550100400'),
    ('user-invalid',     'invalid.delivered@sim.test',   '+15550100500'),
    ('user-autherror',   'autherror.delivered@sim.test', '+15550100600'),
    ('user-flaky2',      'flaky2.delivered@sim.test',    '+15550101200'),
    -- Callback outcomes after a successful send (SMS has no bounce)
    ('user-bounce',      'ok.bounce@sim.test',           null),
    ('user-cb-failed',   'ok.failed@sim.test',           '+15550100002'),
    ('user-silent',      'ok.silent@sim.test',           '+15550100009');

insert into channel_preference (recipient_id, channel, enabled) values
    ('user-sms-optout', 'EMAIL', true),
    ('user-sms-optout', 'SMS',   false);
