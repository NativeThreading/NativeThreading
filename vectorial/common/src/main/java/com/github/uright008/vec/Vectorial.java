package com.github.uright008.vec;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Vectorial {

    public static final String MOD_ID = "vectorial";
    static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private Vectorial() {}

    public static void init() {
        LOGGER.info("Vectorial init — attempting agent attachment");
        try {
            attachAgent();
        } catch (Exception e) {
            LOGGER.warn("Vectorial agent attachment failed — SoA disabled", e);
        }
    }

    private static void attachAgent() throws Exception {
        File jarFile = new File(Vectorial.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());

        if (!jarFile.isFile() || !jarFile.getName().endsWith(".jar")) {
            LOGGER.warn("Not running from a JAR — SoA disabled");
            return;
        }

        File agentJar = extractAgentJar();
        LOGGER.info("Attaching embedded Vectorial agent from: {}", agentJar.getAbsolutePath());
        ByteBuddyAgent.attach(agentJar, String.valueOf(ProcessHandle.current().pid()));
    }

    private static File extractAgentJar() throws IOException {
        try (InputStream source = Vectorial.class.getResourceAsStream("/META-INF/vectorial-agent.jar")) {
            if (source == null) {
                throw new IOException("Embedded Vectorial agent JAR is missing");
            }
            Path agentJar = Files.createTempFile("vectorial-agent-", ".jar");
            Files.copy(source, agentJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            agentJar.toFile().deleteOnExit();
            return agentJar.toFile();
        }
    }
}
