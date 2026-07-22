package com.orbitai.core.security

/**
 * Hard deny-list for shell commands the local agent must NEVER be allowed to
 * run, regardless of the user's permission level (even FULL_ACCESS).
 *
 * The agent runs commands inside the PRoot guest rootfs, but a careless "Allow"
 * on a destructive command could still wipe the rootfs, brick storage symlinks,
 * or open a remote shell. These patterns are evaluated BEFORE the normal
 * permission gate, so they always block.
 *
 * Keep entries as lowercase substrings matched against the lowercased command.
 * This is deliberately conservative (matches common destructive foot-guns).
 */
private val DANGEROUS_PATTERNS: List<String> = listOf(
    "rm -rf", "rm -fr", "rm -r /", "rm -r ~", "rm -rf /", "rm -rf ~",
    "mkfs",
    ":(){", "fork bomb",
    // Network-delivered execution (pipe a download straight into a shell).
    "curl", "| sh", "| bash", "|sudo", "wget", "| python", "| perl", "| ruby",
    "curl ", "wget ", "http://", "https://",  // fetching remote content by URL
    "nc -e", "nc -c", "ncat", "bash -i", "sh -i",  // reverse shells
    "socat", "telnet ",  // common reverse-shell plumbing
    "$(curl", "$(wget", "`curl", "`wget",  // command substitution from a fetch
    "chmod -r", "chmod 777 /", "chmod 777 ",
    "chown -r", "chown root",
    "dd if=", "> /dev/sd", "> /dev/null",
    "mv / ", "mv /data/data",
    "shutdown", "reboot", "halt", "poweroff", "fastboot",
    "format",
    ">:{", // fork-bomb-ish
    "> /sys/", "> /proc/",
    ">/etc/passwd", ">/etc/shadow", ">/etc/sudoers",
    "iptables", "ufw", "nft ",
    "passwd", "usermod", "useradd",
    "crontab",
    "mount ", "umount ",
    "/data/data/com.termux/files/home/storage",
    "setenforce", "selinux",
    "su ", "sudo su"
)
/**
 * Returns true if [cmd] matches a known dangerous pattern and must be blocked.
 * Case-insensitive. Trims and lowercases the input before matching.
 */
internal fun isDangerousCommand(cmd: String): Boolean {
    val c = cmd.trim().lowercase()
    if (c.isEmpty()) return false
    return DANGEROUS_PATTERNS.any { pat -> c.contains(pat) }
}
