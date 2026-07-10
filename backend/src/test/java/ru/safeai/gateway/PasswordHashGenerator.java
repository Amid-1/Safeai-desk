package ru.safeai.gateway;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class PasswordHashGenerator {

    public static void main(String[] args) {

        System.out.println(new BCryptPasswordEncoder().encode("Admin_Dev_2026!Strong#91"));
    }
}