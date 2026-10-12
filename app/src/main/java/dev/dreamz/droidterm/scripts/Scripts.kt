package dev.dreamz.droidterm.scripts

import dev.dreamz.droidterm.data.AppDb
import dev.dreamz.droidterm.data.ScriptEntity
import dev.dreamz.droidterm.term.TermEnv
import java.io.File

/** Saved scripts live in Room and are mirrored to ~/scripts so they can also be run by hand. */
object Scripts {
    val dir: File get() = File(TermEnv.home, "scripts")

    fun fileName(s: ScriptEntity): String {
        val slug = s.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "script" }
        return "$slug-${s.id}.sh"
    }

    fun fileOf(s: ScriptEntity) = File(dir, fileName(s))

    /** Writes the script file and returns the command line that runs it (via su when it needs root). */
    fun prepare(s: ScriptEntity): String {
        dir.mkdirs()
        val f = fileOf(s)
        val body = if (s.body.startsWith("#!")) s.body else "#!/system/bin/sh\n" + s.body
        f.writeText(body.trimEnd() + "\n")
        f.setExecutable(true, false)
        f.setReadable(true, false)
        val path = f.absolutePath
        return if (s.needsRoot) "su -c 'sh $path'" else "sh '$path'"
    }

    fun delete(s: ScriptEntity) {
        fileOf(s).delete()
    }

    /** Adds the starter scripts the first time the Scripts tab opens. */
    suspend fun seedIfEmpty() {
        val dao = AppDb.db.scripts()
        if (dao.count() > 0) return
        STARTERS.forEach { dao.insert(it) }
    }

    val STARTERS = listOf(
        ScriptEntity(
            name = "Chroot: set up Ubuntu",
            description = "Unpacks an Ubuntu Base arm64 rootfs into /data/local/chroot/ubuntu. Needs root.",
            needsRoot = true,
            body = """
                |# Creates an Ubuntu chroot at ${'$'}ROOTFS from an Ubuntu Base tarball.
                |# If the phone has no curl/wget, download ubuntu-base-*-base-arm64.tar.gz from
                |# https://cdimage.ubuntu.com/ubuntu-base/releases/ to /sdcard/Download first.
                |set -e
                |ROOTFS=/data/local/chroot/ubuntu
                |REL=https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release
                |mkdir -p ${'$'}ROOTFS
                |if [ -x ${'$'}ROOTFS/bin/bash ]; then echo "Already set up at ${'$'}ROOTFS"; exit 0; fi
                |TAR=${'$'}(ls /sdcard/Download/ubuntu-base-*-base-arm64.tar.gz 2>/dev/null | tail -1)
                |if [ -z "${'$'}TAR" ]; then
                |  if command -v curl >/dev/null; then GET="curl -fsSL"; GETO="curl -fL -o"
                |  elif command -v wget >/dev/null; then GET="wget -qO-"; GETO="wget -O"
                |  elif command -v busybox >/dev/null; then GET="busybox wget -qO-"; GETO="busybox wget -O"
                |  else echo "No curl/wget/busybox. Download the tarball to /sdcard/Download and run again."; exit 1; fi
                |  NAME=${'$'}(${'$'}GET ${'$'}REL/ | grep -o 'ubuntu-base-[0-9.]*-base-arm64.tar.gz' | sort -u | tail -1)
                |  [ -n "${'$'}NAME" ] || { echo "Couldn't find the tarball name at ${'$'}REL"; exit 1; }
                |  TAR=/data/local/tmp/${'$'}NAME
                |  echo "Downloading ${'$'}NAME"
                |  ${'$'}GETO ${'$'}TAR ${'$'}REL/${'$'}NAME
                |fi
                |echo "Unpacking ${'$'}TAR"
                |tar -xpzf "${'$'}TAR" -C ${'$'}ROOTFS
                |printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > ${'$'}ROOTFS/etc/resolv.conf
                |printf '127.0.0.1 localhost\n' > ${'$'}ROOTFS/etc/hosts
                |# Android's network groups, so apt and ping work inside the chroot.
                |grep -q aid_inet ${'$'}ROOTFS/etc/group || cat >> ${'$'}ROOTFS/etc/group <<'GROUPS'
                |aid_inet:x:3003:root,_apt
                |aid_net_raw:x:3004:root,_apt
                |GROUPS
                |echo 'APT::Sandbox::User "root";' > ${'$'}ROOTFS/etc/apt/apt.conf.d/01-android
                |echo "Done. Run \"Chroot: enter\" next, then: apt update"
                |""".trimMargin(),
        ),
        ScriptEntity(
            name = "Chroot: enter",
            description = "Mounts /dev, /proc, /sys and /sdcard into the chroot and opens bash inside it. Needs root.",
            needsRoot = true,
            body = """
                |ROOTFS=/data/local/chroot/ubuntu
                |[ -x ${'$'}ROOTFS/bin/bash ] || { echo "No chroot at ${'$'}ROOTFS. Run \"Chroot: set up Ubuntu\" first."; exit 1; }
                |mnt() { mountpoint -q "${'$'}2" 2>/dev/null || mount ${'$'}1 "${'$'}2"; }
                |mkdir -p ${'$'}ROOTFS/sdcard
                |mnt "-o bind /dev" ${'$'}ROOTFS/dev
                |mnt "-t devpts devpts" ${'$'}ROOTFS/dev/pts
                |mnt "-t proc proc" ${'$'}ROOTFS/proc
                |mnt "-t sysfs sysfs" ${'$'}ROOTFS/sys
                |mnt "-o bind /sdcard" ${'$'}ROOTFS/sdcard
                |mkdir -p ${'$'}ROOTFS/dev/shm; mnt "-t tmpfs tmpfs" ${'$'}ROOTFS/dev/shm
                |exec chroot ${'$'}ROOTFS /usr/bin/env -i HOME=/root TERM=${'$'}TERM LANG=C.UTF-8 \
                |  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /bin/bash -l
                |""".trimMargin(),
        ),
        ScriptEntity(
            name = "Chroot: unmount",
            description = "Unmounts everything \"Chroot: enter\" mounted. Needs root.",
            needsRoot = true,
            body = """
                |ROOTFS=/data/local/chroot/ubuntu
                |for m in sdcard dev/shm sys proc dev/pts dev; do
                |  umount -l ${'$'}ROOTFS/${'$'}m 2>/dev/null && echo "unmounted /${'$'}m"
                |done
                |echo done
                |""".trimMargin(),
        ),
        ScriptEntity(
            name = "Device report",
            description = "Model, Android version, battery, storage, network and uptime in one go.",
            body = """
                |echo "== Device"; getprop ro.product.manufacturer; getprop ro.product.model
                |echo "Android ${'$'}(getprop ro.build.version.release) (SDK ${'$'}(getprop ro.build.version.sdk)), patch ${'$'}(getprop ro.build.version.security_patch)"
                |echo "== Battery"; dumpsys battery 2>/dev/null | grep -E 'level|status|temperature|health' || echo "(needs adb/root)"
                |echo "== Storage"; df -h /data /sdcard 2>/dev/null
                |echo "== Network"; ip -br addr 2>/dev/null | grep -v '^lo'
                |echo "== Uptime"; uptime
                |""".trimMargin(),
        ),
        ScriptEntity(
            name = "ADB: back up app list",
            description = "Saves the third-party app list of every connected adb device to ~/backups.",
            body = """
                |mkdir -p ${'$'}HOME/backups
                |for s in ${'$'}(adb devices | awk 'NR>1 && ${'$'}2=="device" {print ${'$'}1}'); do
                |  out=${'$'}HOME/backups/apps-${'$'}(echo ${'$'}s | tr ':.' '__')-${'$'}(date +%Y%m%d).txt
                |  adb -s ${'$'}s shell pm list packages -3 | sed 's/^package://' | sort > ${'$'}out
                |  echo "${'$'}s: ${'$'}(wc -l < ${'$'}out) apps -> ${'$'}out"
                |done
                |""".trimMargin(),
        ),
    )
}
