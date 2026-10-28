package com.gogo.travel.agent.hook;

import io.agentscope.core.tool.coding.CommandValidator.ValidationResult;
import io.agentscope.core.tool.coding.WindowsCommandValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("os.name")
class TuniuApiKeyHookTest {

    private final TuniuApiKeyHook hook = new TuniuApiKeyHook();

    @Test
    void usesCmdEnvironmentInjectionOnWindows() {
        withOsName("Windows 11", () -> {
            String transformed = hook.transformCommand("tuniu --version", "sk-test_123");

            assertEquals(
                    "cmd /d /s /c \"set TUNIU_API_KEY=sk-test_123&&tuniu --version\"",
                    transformed);

            ValidationResult validation = new WindowsCommandValidator()
                    .validate(transformed, Set.of("cmd"));
            assertTrue(validation.isAllowed());
        });
    }

    @Test
    void convertsPosixQuotedJsonForWindowsCmd() {
        withOsName("Windows 11", () -> {
            String command = "tuniu call hotel tuniuHotelSearch -a '{\"cityName\":\"北京\"}' --dry-run";

            assertEquals(
                    "cmd /d /s /c \"set TUNIU_API_KEY=sk-test&&tuniu call hotel "
                            + "tuniuHotelSearch -a \"{\\\"cityName\\\":\\\"北京\\\"}\" --dry-run\"",
                    hook.transformCommand(command, "sk-test"));
        });
    }

    @Test
    void leavesCommandUnchangedWhenApiKeyContainsShellMetacharacters() {
        withOsName("Windows 11", () -> {
            String command = "tuniu --version";

            assertEquals(command, hook.transformCommand(command, "sk-test&&whoami"));
        });
    }

    @Test
    void doesNotHandleTuniuEmbeddedInAnotherCommand() {
        assertFalse(hook.shouldHandle("echo test && tuniu list"));
    }

    @Test
    void leavesChainedWindowsCommandUnchanged() {
        withOsName("Windows 11", () -> {
            String command = "tuniu list && whoami";

            assertEquals(command, hook.transformCommand(command, "sk-test"));
        });
    }

    @Test
    void doesNotTreatBackslashAsAWindowsCommandSeparatorEscape() {
        withOsName("Windows 11", () -> {
            String command = "tuniu list \\& whoami";

            assertEquals(command, hook.transformCommand(command, "sk-test"));
        });
    }

    @Test
    void keepsEnvInjectionOnUnix() {
        withOsName("Linux", () -> assertEquals(
                "env TUNIU_API_KEY=sk-test tuniu --version",
                hook.transformCommand("tuniu --version", "sk-test")));
    }

    private static void withOsName(String osName, Runnable assertion) {
        String original = System.getProperty("os.name");
        try {
            System.setProperty("os.name", osName);
            assertion.run();
        } finally {
            if (original == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", original);
            }
        }
    }
}
