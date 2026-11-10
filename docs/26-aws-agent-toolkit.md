# AWS Agent Toolkit

AWS Agent Toolkit provides AWS skills and an AWS MCP connection for local coding
agents. It is configured separately from the backend application's S3 credentials.
The backend's local startup does not depend on this toolkit.

## Local configuration

The Windows setup uses AWS CLI v2 and `uv`/`uvx`. AWS CLI was installed per user
under `%LOCALAPPDATA%\Programs\Amazon\AWSCLIV2` using the official signed installer.
Open a new terminal after installation so its PATH includes the CLI.

The toolkit installed 24 default skills for each detected agent:

- Codex: `%USERPROFILE%\.agents\skills`.
- Claude Code: `%USERPROFILE%\.claude\skills`.

The generated `aws-mcp` entries are in `%USERPROFILE%\.codex\config.toml` and
`%USERPROFILE%\.claude.json`. Each entry sets `AWS_MCP_PROXY_PROFILES` to the
authenticated named profile. Preserve the generated command and arguments when
updating this environment variable. Credentials and personal MCP settings stay
outside the repository.

The new AWS experience guidance is enclosed by stable `AWS Agent Toolkit rules`
markers in the project's `AGENTS.md` and `CLAUDE.md`. Existing project instructions
take precedence. Refresh only the marked block when updating these AWS rules.

## Sign-in and verification

Use the same named profile for all commands. Replace `your-profile` below with
your local AWS profile name:

```powershell
$awsProfile = 'your-profile'
aws login --region us-east-1 --profile $awsProfile
aws sts get-caller-identity --profile $awsProfile --no-cli-pager
aws agent-toolkit list-available-skills --region us-east-1 --profile $awsProfile --no-cli-pager
```

The selected project Region for this setup is `us-east-1`. For a different project,
confirm its assigned Region in AWS Settings before setting the profile Region or
deploying resources. The toolkit service itself uses `us-east-1` regardless of the
project Region.

If catalog output fails with a Windows `charmap` encoding error, set these variables
in that terminal and retry:

```powershell
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
```

Verification on 2026-10-31 confirmed an authenticated identity, both profile
bindings, 24 skill files per agent, and a remote catalog of 118 skills containing
`name`, `description`, `skillVersion`, and `categories` fields. Restart the coding
agent to load its new MCP configuration and skills.

To use another AWS project later, authenticate with `aws login --profile <name>`,
add the profile name to the space-separated `AWS_MCP_PROXY_PROFILES` value in each
MCP configuration, and restart the coding agent.

## Sources

- [AWS setup instructions](https://raw.githubusercontent.com/aws/agent-toolkit-for-aws/refs/heads/main/setup-instructions/setup.md)
- [AWS troubleshooting](https://raw.githubusercontent.com/aws/agent-toolkit-for-aws/refs/heads/main/setup-instructions/setup-troubleshooting.md)
- [New AWS experience rules](https://raw.githubusercontent.com/aws/agent-toolkit-for-aws/refs/heads/main/rules/aws-starter-rules.md)
