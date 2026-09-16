package com.classroomscanner.fragments

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.classroomscanner.databinding.FragmentPermissionsBinding

private val PERMISSIONS_REQUIRED = arrayOf(Manifest.permission.CAMERA)

/** Asks for the camera permission, explains why, and continues to the scanner once granted. */
class PermissionsFragment : Fragment() {

    private val args: PermissionsFragmentArgs by navArgs()
    private var navigated = false

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && isResumed) navigateToCamera()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!hasPermissions(requireContext())) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = FragmentPermissionsBinding.inflate(inflater, container, false)
        binding.grantButton.setOnClickListener {
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                // Permanently denied: the system dialog will not show again, so open app settings.
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", requireContext().packageName, null))
                )
            }
        }
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        if (hasPermissions(requireContext())) navigateToCamera()
    }

    private fun navigateToCamera() {
        if (navigated) return
        navigated = true
        findNavController().navigate(PermissionsFragmentDirections.actionPermissionsToCamera(args.mode))
    }

    companion object {
        fun hasPermissions(context: Context) = PERMISSIONS_REQUIRED.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
