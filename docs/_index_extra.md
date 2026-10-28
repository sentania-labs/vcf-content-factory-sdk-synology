### Removed objects are deleted (build 35)

An object DSM no longer reports is marked Not Existing after 12
consecutive complete collection cycles (an hour at 5 minutes), and VCF
Operations deletes it, with its history, alerts and group membership, after
the Deleted Objects retention (168 hours by default). This cannot be
undone, and the first upgrade does it at once for every object from before
build 34. A kind counts a miss only in a cycle that read it in full: a
failed read counts nothing, nor does an empty list without DSM's own
evidence, nor any storage kind while DSM reports a pool missing or awaiting
assembly (an expansion unit unplugged, drives pulled). The last LUN, NFS
export and volume on a pool are removed by hand. Rules and edge cases:
[REFERENCE.md, Removed objects](../REFERENCE.md#removed-objects); upgrade
note: [README.md, To build 35](../README.md#to-build-35-objects-dsm-no-longer-reports-are-deleted-and-the-first-upgrade-deletes-the-pre-build-34-leftovers).

### Alerts

The pak ships nine alerts with their symptoms and recommendations (build
35): Storage Pool Not Healthy, Volume Not Healthy, Volume Nearly Full,
Disk Failing, SSD Cache Failure, Fan Failure, UPS On Battery, Storage
Degraded and Expansion Unit Lost. What each
fires on, its object and severity: [REFERENCE.md, Bundled alerts](../REFERENCE.md#bundled-alerts).
