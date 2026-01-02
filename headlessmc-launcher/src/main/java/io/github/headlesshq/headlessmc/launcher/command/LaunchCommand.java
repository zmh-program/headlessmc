package io.github.headlesshq.headlessmc.launcher.command;

import lombok.CustomLog;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import io.github.headlesshq.headlessmc.api.command.CommandException;
import io.github.headlesshq.headlessmc.api.command.CommandUtil;
import io.github.headlesshq.headlessmc.launcher.Launcher;
import io.github.headlesshq.headlessmc.launcher.LauncherProperties;
import io.github.headlesshq.headlessmc.launcher.auth.AuthException;
import io.github.headlesshq.headlessmc.launcher.auth.LaunchAccount;
import io.github.headlesshq.headlessmc.auth.ValidatedAccount;
import io.github.headlesshq.headlessmc.launcher.command.download.AbstractDownloadingVersionCommand;
import io.github.headlesshq.headlessmc.launcher.launch.LaunchException;
import io.github.headlesshq.headlessmc.launcher.launch.LaunchOptions;
import io.github.headlesshq.headlessmc.launcher.util.JsonUtil;
import io.github.headlesshq.headlessmc.launcher.util.URLs;
import io.github.headlesshq.headlessmc.launcher.version.Version;
import net.lenni0451.commons.httpclient.HttpClient;
import net.lenni0451.commons.httpclient.requests.impl.GetRequest;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.responsehandler.MinecraftResponseHandler;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URL;
import java.util.Locale;

@CustomLog
public class LaunchCommand extends AbstractDownloadingVersionCommand {
    private static final URL PROFILE_URL = URLs.url("https://api.minecraftservices.com/minecraft/profile");

    public LaunchCommand(Launcher launcher) {
        super(launcher, "launch", "Launches the game.");
        args.put("<version/id>", "Name or id of the version to launch. If you use the id you need to use the -id flag as well.");
        args.put("-id", "Use if you specified an id instead of a version name.");
        args.put("-commands", "Starts the game with the built-in command line support.");
        args.put("-lwjgl", "Removes lwjgl code, causing Minecraft not to render anything.");
        args.put("-inmemory", "Launches the game in the same JVM headlessmc is running in.");
        args.put("-jndi", "Patches the Log4J vulnerability.");
        args.put("-lookup", "Patches the Log4J vulnerability even harder.");
        args.put("-paulscode", "Removes some error messages from the PaulsCode library which may annoy you if you started the game with the -lwjgl flag.");
        args.put("-noout", "Doesn't print Minecrafts output to the console."); // TODO: is this really necessary?
        args.put("-quit", "Quit HeadlessMc after launching the game.");
        args.put("-offline", "Launch Mc in offline mode.");
        args.put("--jvm", "Jvm args to use.");
        args.put("--account", "Account username or uuid to use for this launch.");
        args.put("--accessToken", "Access token to use for this launch.");
        args.put("--retries", "The amount of times you want to retry running Minecraft.");
    }

    @Override
    public void execute(Version version, String... args) throws CommandException {
        ClientLaunchProcessLifecycle lifecycle = new ClientLaunchProcessLifecycle(version, args);
        lifecycle.run(version);
    }

    private class ClientLaunchProcessLifecycle extends AbstractLaunchProcessLifecycle {
        private final Version version;
        private @Nullable LaunchAccount account;

        public ClientLaunchProcessLifecycle(Version version, String[] args) {
            super(LaunchCommand.this.ctx, args);
            this.version = version;
        }

        @Override
        protected void getAccount() throws CommandException {
            this.account = LaunchCommand.this.getAccount(args);
        }

        @Override
        protected Path getGameDir() {
            return Paths.get(ctx.getConfig().get(LauncherProperties.GAME_DIR, ctx.getGameDir(version).getPath())).toAbsolutePath();
        }

        @Override
        protected @Nullable Process createProcess() throws LaunchException, AuthException, IOException {
            return ctx.getProcessFactory().run(
                    LaunchOptions.builder()
                            .account(account)
                            .version(version)
                            .launcher(ctx)
                            .files(files)
                            .closeCommandLine(!prepare)
                            .parseFlags(ctx, quit, args)
                            .prepare(prepare)
                            .build()
            );
        }
    }

    protected LaunchAccount getAccount(String... args) throws CommandException {
        String accessToken = CommandUtil.getOption("--accessToken", args);
        String accountArg = CommandUtil.getOption("--account", args);

        if (accessToken != null) {
            return getAccountForToken(accessToken, accountArg);
        }

        if (accountArg != null) {
            ValidatedAccount account = findAccount(accountArg);
            if (account == null) {
                throw new CommandException("Couldn't find account for name or uuid '" + accountArg + "'!");
            }

            return toLaunchAccount(maybeRefreshAccount(account));
        }

        return getPrimaryAccount();
    }

    private LaunchAccount toLaunchAccount(ValidatedAccount account) {
        return new LaunchAccount("msa",
                account.getSession().getMcProfile().getName(),
                account.getSession().getMcProfile().getId().toString(),
                account.getSession().getMcProfile().getMcToken().getAccessToken(),
                account.getXuid());
    }

    private LaunchAccount getPrimaryAccount() throws CommandException {
        ValidatedAccount account = ctx.getAccountManager().getPrimaryAccount();
        if (account == null) {
            if (ctx.getAccountManager().getOfflineChecker().isOffline()) {
                try {
                    return ctx.getAccountManager().getOfflineAccount(ctx.getConfig());
                } catch (AuthException e) {
                    throw new CommandException(e.getMessage());
                }
            }

            throw new CommandException("You can't play the game without an account! Please use the login command.");
        }

        return toLaunchAccount(maybeRefreshAccount(account));
    }

    private LaunchAccount getAccountForToken(String accessToken, @Nullable String accountArg) throws CommandException {
        LaunchAccount tokenAccount = fetchAccountFromToken(accessToken);
        if (accountArg != null && !matchesAccountArg(tokenAccount, accountArg)) {
            throw new CommandException("Access token does not match account '" + accountArg + "'!");
        }

        return tokenAccount;
    }

    private ValidatedAccount maybeRefreshAccount(ValidatedAccount account) throws CommandException {
        if (!ctx.getConfig().get(LauncherProperties.REFRESH_ON_GAME_LAUNCH, true)) {
            return account;
        }

        try {
            return ctx.getAccountManager().refreshAccount(account, ctx.getConfig());
        } catch (AuthException e) {
            if (ctx.getConfig().get(LauncherProperties.FAIL_LAUNCH_ON_REFRESH_FAILURE, false)) {
                throw new CommandException(e.getMessage());
            }
        }

        return account;
    }

    private @Nullable ValidatedAccount findAccount(String accountArg) {
        String normalizedArg = normalizeUuid(accountArg);
        for (ValidatedAccount account : ctx.getAccountManager().getAccounts()) {
            if (account.getName().equalsIgnoreCase(accountArg)) {
                return account;
            }

            String uuid = account.getSession().getMcProfile().getId().toString();
            if (uuid.equalsIgnoreCase(accountArg)) {
                return account;
            }

            if (normalizedArg != null && normalizedArg.equals(normalizeUuid(uuid))) {
                return account;
            }
        }

        return null;
    }

    private LaunchAccount fetchAccountFromToken(String accessToken) throws CommandException {
        try {
            HttpClient httpClient = MinecraftAuth.createHttpClient();
            GetRequest request = new GetRequest(PROFILE_URL);
            request.appendHeader("Authorization", "Bearer " + accessToken);
            JsonObject json = httpClient.execute(request, new MinecraftResponseHandler());
            String name = JsonUtil.getString(json, "name");
            String id = JsonUtil.getString(json, "id");
            if (name == null || id == null) {
                throw new CommandException("Failed to read profile from access token.");
            }

            return new LaunchAccount("msa", name, formatUuid(id), accessToken, "");
        } catch (IOException | JsonParseException e) {
            throw new CommandException("Failed to fetch profile from access token: " + e.getMessage());
        }
    }

    private boolean matchesAccountArg(LaunchAccount account, String accountArg) {
        if (account.getName().equalsIgnoreCase(accountArg)) {
            return true;
        }

        String normalizedArg = normalizeUuid(accountArg);
        String normalizedId = normalizeUuid(account.getId());
        return normalizedArg != null && normalizedArg.equals(normalizedId);
    }

    private String formatUuid(String value) {
        String normalized = normalizeUuid(value);
        if (normalized == null) {
            return value;
        }

        return normalized.substring(0, 8) + "-"
            + normalized.substring(8, 12) + "-"
            + normalized.substring(12, 16) + "-"
            + normalized.substring(16, 20) + "-"
            + normalized.substring(20);
    }

    private @Nullable String normalizeUuid(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.replace("-", "").toLowerCase(Locale.ENGLISH);
        if (normalized.length() != 32) {
            return null;
        }

        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch < '0' || (ch > '9' && ch < 'a') || ch > 'f') {
                return null;
            }
        }

        return normalized;
    }

}
