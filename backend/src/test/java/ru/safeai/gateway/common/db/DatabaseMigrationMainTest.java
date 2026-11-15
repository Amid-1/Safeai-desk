package ru.safeai.gateway.common.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseMigrationMainTest {

    @Test
    void refusesUnreviewedMigrationCommands() {
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseMigrationMain.main(new String[]{"clean"}));
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseMigrationMain.main(new String[]{"repair"}));
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseMigrationMain.main(new String[]{"ci-v85", "extra"}));
    }
}
