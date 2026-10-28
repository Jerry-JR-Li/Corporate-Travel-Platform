package com.gogo.travel.agent.hook;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 途牛 API Key 注入 Hook。
 *
 * <p>监听 {@link io.agentscope.core.hook.PreActingEvent}：当检测到 {@code execute_shell_command} 工具调用
 * 且命令以 {@code tuniu} 开头时，在命令前注入环境变量 {@code TUNIU_API_KEY}，
 * 使 tuniu CLI 使用当前用户的 API Key。</p>
 *
 * @author Hollis
 */
@Component
public class TuniuApiKeyHook extends AbstractShellApiKeyHook {

    private static final String TUNIU_CMD = "tuniu ";
    private static final String ENV_PREFIX = "TUNIU_API_KEY=";
    private static final String EXPORT_PREFIX = "export TUNIU_API_KEY";
    private static final String ENV_INJECT_PREFIX = "env TUNIU_API_KEY=";
    private static final String WINDOWS_INJECT_PREFIX = "cmd /d /s /c \"set TUNIU_API_KEY=";
    private static final Pattern SAFE_API_KEY = Pattern.compile("[A-Za-z0-9._-]+");

    @Override
    protected String providerName() {
        return "tuniu-cli";
    }

    @Override
    protected String logTag() {
        return "TuniuApiKey";
    }

    @Override
    protected boolean shouldHandle(String command) {
        // 只处理直接调用的 tuniu 命令，避免包装器扩大复合命令的执行权限。
        String trimmed = command.stripLeading();
        return trimmed.startsWith(TUNIU_CMD)
                && !command.contains(ENV_PREFIX)
                && !command.contains(EXPORT_PREFIX);
    }

    @Override
    protected String transformCommand(String command, String apiKey) {
        if (apiKey == null || !SAFE_API_KEY.matcher(apiKey).matches()) {
            logger.warn("[{}] API Key contains unsupported characters; skipping shell injection", logTag());
            return command;
        }

        if (isWindows()) {
            if (containsCommandSeparator(command)) {
                logger.warn("[{}] Refusing to wrap a compound Windows command", logTag());
                return command;
            }

            String windowsCommand = convertPosixQuotedArguments(command);
            if (windowsCommand == null) {
                logger.warn("[{}] Refusing to wrap a command with unmatched quotes", logTag());
                return command;
            }
            return WINDOWS_INJECT_PREFIX + apiKey + "&&" + windowsCommand + "\"";
        }

        // Unix shells support env as a per-process environment-variable prefix.
        return ENV_INJECT_PREFIX + apiKey + " " + command;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean containsCommandSeparator(String command) {
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;

        for (int i = 0; i < command.length(); i++) {
            char current = command.charAt(i);
            if (current == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
                continue;
            }
            if (current == '"' && !inSingleQuotes && !isBackslashEscaped(command, i)) {
                inDoubleQuotes = !inDoubleQuotes;
                continue;
            }
            if (!inSingleQuotes && !inDoubleQuotes
                    && (current == '&' || current == '|' || current == '\r' || current == '\n')) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBackslashEscaped(String value, int index) {
        int backslashes = 0;
        for (int i = index - 1; i >= 0 && value.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 != 0;
    }

    /**
     * The bundled skill uses POSIX single quotes around JSON. cmd.exe treats those quotes as data,
     * so convert each single-quoted argument to a double-quoted Windows argument.
     */
    private static String convertPosixQuotedArguments(String command) {
        StringBuilder converted = new StringBuilder(command.length() + 16);
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        boolean escaped = false;

        for (int i = 0; i < command.length(); i++) {
            char current = command.charAt(i);
            if (escaped) {
                converted.append(current);
                escaped = false;
                continue;
            }
            if (current == '\\' && !inSingleQuotes) {
                converted.append(current);
                escaped = true;
                continue;
            }
            if (current == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
                converted.append('"');
                continue;
            }
            if (current == '"' && inSingleQuotes) {
                converted.append("\\\"");
                continue;
            }
            if (current == '"') {
                inDoubleQuotes = !inDoubleQuotes;
            }
            converted.append(current);
        }

        return inSingleQuotes || inDoubleQuotes ? null : converted.toString();
    }
}
