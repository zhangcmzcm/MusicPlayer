package org.example.music;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class MusicApplicationTests {

    @Test
    void should_expose_application_entry_point() {
        assertNotNull(MusicApplication.class);
    }

}