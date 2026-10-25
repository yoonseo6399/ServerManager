import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.*;
import javax.naming.ldap.LdapName;

/** Build-time verification of the default trust stores; see https://ops.java/emudhra. */
public final class RuntimeTrust {
    private static final Set<String> BLOCKED = Set.of("emSign ECC Root CA - G3", "AAA Certificate Services");
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !Set.of("check", "harden").contains(args[0]))
            throw new IllegalArgumentException("Usage: RuntimeTrust check|harden <cacerts>");
        Path file = Path.of(args[1]);
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file))
            throw new IllegalArgumentException("A regular, non-linked trust store is required: " + file);
        KeyStore store = KeyStore.getInstance(file.toFile(), "changeit".toCharArray());
        if (store.size() < 20) throw new IllegalStateException("Unexpectedly small trust store");
        List<String> blocked = new ArrayList<>();
        for (String alias : Collections.list(store.aliases())) {
            if (store.getCertificate(alias) instanceof X509Certificate cert) {
                for (var rdn : new LdapName(cert.getSubjectX500Principal().getName()).getRdns()) {
                    if (rdn.getType().equalsIgnoreCase("CN") && BLOCKED.contains(rdn.getValue().toString())) {
                        blocked.add(alias);
                        break;
                    }
                }
            }
        }
        if (args[0].equals("check") && !blocked.isEmpty())
            throw new IllegalStateException("CVE-2026-91403: affected trust anchors remain: " + blocked);
        if (!blocked.isEmpty()) {
            for (String alias : blocked) store.deleteEntry(alias);
            Set<PosixFilePermission> mode = Files.getFileStore(file).supportsFileAttributeView("posix")
                ? Files.getPosixFilePermissions(file) : null;
            Path tmp = Files.createTempFile(file.getParent(), ".cacerts-", ".tmp");
            try {
                try (var out = Files.newOutputStream(tmp)) { store.store(out, "changeit".toCharArray()); }
                if (mode != null) Files.setPosixFilePermissions(tmp, mode);
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(tmp); }
        }
        System.out.println("CVE-2026-91403 trust store verified; removed=" + blocked.size() + "; entries=" + store.size());
    }
}
