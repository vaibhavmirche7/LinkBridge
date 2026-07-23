package com.vaibhavmirche.linkbridge.ui

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * The stock zxing CaptureActivity follows sensor orientation, which opens the scanner
 * horizontally on most phones. Locking this activity to portrait in the manifest keeps
 * the scan screen vertical by default.
 */
class PortraitCaptureActivity : CaptureActivity()
