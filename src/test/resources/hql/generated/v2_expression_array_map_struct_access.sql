-- Array/map subscript and nested struct field access preserve distinct field paths.
INSERT INTO dwd.result(id, tag, name, amount)
SELECT u.id, u.tags[0], u.attributes['label'], u.profile.account.score
FROM ods.complex_users u;
