# LinkBridge

A local file-sharing app for Android. Send and receive files between devices over Wi-Fi or a hotspot or devive to device, mobile to laptop/PC no cables at all.


## The problem it solves

You need to get a file from your laptop to your desktop, or from your phone to a friend's PC, and you just want to send it and move on. But:

* You don't have a USB stick or cable within reach
* You don't want to set up SMB enabling Samba, then hunting down a client for it
* You don't want to go through the cloud, because it's slow, it's not private, or you just don't have it handy

LinkBridge skips all of that. Open the app, connect, send the file.

## Key features

* **Effortless LAN sharing** : Start the server and it serves files from your chosen shared folder over HTTP. Any device on the same Wi-Fi or hotspot can connect with the web address shown in the app, or by scanning a QR code.
* **LAN discovery** : Find other devices running LinkBridge on the same network automatically, via NSD/mDNS. No typing in IP addresses.
* **Cross-network QR pairing** : Not on the same network? Scan a QR code (or, for nearby devices, skip the scan entirely — LinkBridge can find them over Bluetooth LE, similar to Nearby Share) to pair over the internet, then transfer directly device-to-device over WebRTC.
* **Built-in security**:
  * **IP approval** : By default, a device connecting for the first time triggers an Allow/Deny prompt on your phone. Turn it off if you're on a network you trust.
  * **Per-transfer approval** : Before any batch of files lands, you get a prompt showing exactly what's incoming names, sizes with Accept/Decline.
  * **Web login required** : Every web connection needs to log in, and that login becomes the device's name throughout the app, so you always know who's connected.
  * **Configurable port** : Change the server port from the default (8000) right on the LAN screen.
* **CLI-friendly** : LinkBridge plays nicely with `curl`. Upload with `curl -T yourfile.txt <phone-ip>:<port>`, download with `curl <phone-ip>:<port>/yourfile.txt`.
* **Two ways to browse** : Manage your shared files from inside the app, or from the web interface on any connected computer.
* **Quick in-app actions**:
  * **Upload** : pull files straight from your phone's storage into the shared folder.
  * **Paste** : drop clipboard text into a new `.txt` file with one tap.
* **Logs & history** : See who's connected and when (3-dot menu → Logs), plus a full record of every transfer: filename, size, direction, timestamp, mode, device, and status (Home → Log/History).

## Getting the app

Not yet published to F-Droid or Google Play. Build it from source for now.

# Future Implementation

few exciting features planned for upcoming releases

  * Server mode will be added in a way that feels similar to uploading files to a cloud service, except the transfer will stay local and direct. A user will be able to upload a file and share it through a link or code, so another device can download it easily without any cables or complicated setup.

  * End-to-end encryption (E2EE) is also planned for a future update to strengthen privacy and make transfers even more secure.

More improvements are also on the roadmap to make LinkBridge faster, smoother, and more reliable across different devices and network conditions.

# Contributing

* LinkBridge is open to contributions, and collaboration is welcome. If you’re interested in improving the app, fixing bugs, or helping with new features, your contributions would be greatly appreciated.
