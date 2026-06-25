/*backend/src/main/resources/db/migration/V23__reset_super_admin_password.sql*/
update users
set password_hash = '$2a$10$yJB.CHsf1cPn3lYot0djHuiBE4Dk7o8iFkZdCNLAoaCy5TFvIxS36',
    token_version = token_version + 1
where email = 'superadmin@test.com';