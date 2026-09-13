Harmonicast shared-access library setup
======================================

1. Create a dedicated folder named Harmonicast OUTSIDE your existing music
   library folders. It can be on a local disk, NAS, or Windows/SMB share.

2. Extract the CONTENTS of harmonicast-plex-setup.zip into that folder.
   If your extractor creates an extra folder named harmonicast-plex-setup,
   move its contents up into your dedicated Harmonicast folder.

   The final layout should be:
   Harmonicast/                          <- Plex library folder
     README.txt
     Harmonicast/                        <- artist folder (expected)
       Shared Access Setup/
         01-setup.flac

   The two Harmonicast folders are intentional: one is the library root and
   one is the artist. Point Plex at the OUTER folder. The audio file already
   has the artist, album, title, album artist, and track number tags filled in.
   No script needs to run on the server or NAS. Extract the ZIP; do not leave
   the audio inside the archive.

3. Make the outer folder visible and readable to Plex.
   - Native Plex: use its full local path or an accessible share path.
   - Docker with a mapped media parent: e.g. host /mnt/user/media/Harmonicast
     is /media/Harmonicast when /mnt/user/media is mapped to /media.
   - Docker with separate library mappings: add a mapping for the new folder,
     e.g. host /mnt/user/media/Harmonicast to container /harmonicast.
   - NAS/SMB: extract from Explorer, Finder, or your file manager. The account
     Plex uses must have permission to list the folders and read the file.
     Your computer being able to read the share does not prove Plex can.
   - Unix: 755 directories and a 644 audio file are suitable for these public
     setup files. ZIP tools may ignore stored permissions. Parent directories
     must also allow Plex to traverse them. Do not recursively change the
     permissions or ownership of your existing media. No 777 is needed.

4. In Harmonicast, while signed in as the Plex server owner, open Settings >
   Music acquisition > Set up shared access. Enter the OUTER folder's path
   AS PLEX SEES IT. For example, copying to \\NAS\Media\Harmonicast on Windows
   might correspond to /media/Harmonicast inside the Plex container.

5. Choose Create setup library (or Use this folder), let Plex scan, then choose
   Prepare library when the app identifies the setup album. Harmonicast fills
   in and verifies the installation-specific, inactive configuration record.
   No manual Summary/Review editing is needed. Shared acquisition remains off
   until you explicitly publish the dedicated account in the next step.

6. Choose Connect dedicated account. Enter the reachable HTTPS MusicGrabber URL
   and a dedicated peon/user login, either here or through Enter shared login
   from a computer. Test the account, verify that its downloads reach the intended
   Plex music library, and review the named server, library, account and room
   permission before choosing Publish account.

7. Share the Harmonicast library in Plex only with intended recipients who also
   have access to your music library. They select that music library in Harmonicast
   and refresh shared access in Music acquisition settings. They do not enter the
   MusicGrabber password themselves.

Everyone who can read the setup library can read and copy the account password.
Removing Plex access blocks new app requests after verification; rotate the
MusicGrabber password to revoke copied credentials. Accepted jobs may continue.

Recovery
--------
If Plex cannot find the album, check extraction, mapping, and permissions, then
choose Scan for setup file. If the audio file is deleted, restore it from this
ZIP and rescan. If the inactive configuration record is cleared, run setup
again to review and restore it. Existing unrelated metadata is preserved.

If you already have a working Harmonicast Sharing Proof album from the test
kit, keep using it. Do not add this album alongside it: the setup library must
contain only one setup album.

This archive contains no passwords, tokens, server IDs, or executable scripts.
MusicGrabber requires no changes. The local download page expires after five
minutes; reopen it in the app if needed. The extracted files do not expire.
