# Managed backup retention

Android enrollment and the server's managed deployment identity each
define an independent scope. Server automatic backups use a dedicated
directory and Drive subfolder. Existing manual archives and legacy
server jobs stay outside the new scope. Android manual Drive copies
have no managed deletion record.

Keep two verified generations per destination after successful
pruning. Failed transfers leave prior verified files intact. Local
pending generations may temporarily exceed the limit. Two newer
verified remote generations allow cleanup of superseded pending copies.
Retained remote ciphertext is read back before deletion. The server
logs managed archive names and Android logs opaque generation IDs.
An interrupted deletion can be retried.

Before enabling server retention, verify the dedicated Drive destination,
independent age key, real upload and read-back, and isolated restore.
Server deletion requires LIFEAPP_BACKUP_PRUNE=1. Android enrollment
authorizes pruning of its managed generations for the enrolled account.
