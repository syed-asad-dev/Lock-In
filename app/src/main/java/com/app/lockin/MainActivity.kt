package com.app.lockin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.app.lockin.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        LockInScreen()
      }
    }
  }
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockInScreen() {
  val context = LocalContext.current
  var isDeviceOwner by remember { mutableStateOf(checkIsDeviceOwner(context)) }
  var isAdminActive by remember { mutableStateOf(checkIsAdminActive(context)) }
  var refreshTrigger by remember { mutableIntStateOf(0) }

  LaunchedEffect(Unit) {
    Log.e("MainActivity", "LaunchedEffect: RUNNING INITIAL SCAN")
    val pm = context.packageManager
    val installedPackages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
    } else {
      @Suppress("DEPRECATION")
      pm.getInstalledPackages(0)
    }

    Log.e("MainActivity", "Initial scan: Found ${installedPackages.size} packages.")

    installedPackages.forEach { packageInfo ->
      val pkgName = packageInfo.packageName
      
      // Observe-only quarantine logging
      NetworkQuarantineManager.logQuarantineDecision(packageInfo)

      if (AppBlocklistManager.isPackageBlocked(context, pkgName)) {
        Log.e("MainActivity", "Initial scan: Found blocked package $pkgName. Blocking...")
        AppBlocklistManager.blockPackageIfNeeded(context, pkgName)
      }
    }
  }

  fun refreshStatus() {
    isDeviceOwner = checkIsDeviceOwner(context)
    isAdminActive = checkIsAdminActive(context)
    refreshTrigger++
  }

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer =
      LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
          refreshStatus()
        }
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Default.Security,
              contentDescription = "Lock-In Icon",
              modifier = Modifier.size(28.dp),
              tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(text = "Lock-In", fontWeight = FontWeight.Bold)
          }
        },
        actions = {
          IconButton(
            onClick = { refreshStatus() },
            modifier = Modifier.testTag("refresh_button_appbar"),
          ) {
            Icon(
              imageVector = Icons.Default.Refresh,
              contentDescription = "Refresh Status",
            )
          }
        },
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
          ),
      )
    }
  ) { innerPadding ->
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(innerPadding)
          .padding(horizontal = 20.dp, vertical = 16.dp)
          .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      // Primary Status Indicator Card
      StatusCard(
        isDeviceOwner = isDeviceOwner,
        isAdminActive = isAdminActive,
        modifier = Modifier.testTag("status_card"),
      )

      // Device Owner Details Card
      DeviceOwnerInfoCard(
        isDeviceOwner = isDeviceOwner,
        modifier = Modifier.testTag("device_owner_info_card"),
      )

      // ADB Provisioning Helper Card
      ProvisioningInstructionCard(
        packageName = context.packageName,
        modifier = Modifier.testTag("provisioning_instruction_card"),
      )

      // DNS Lock Card
      DnsLockCard(
        isDeviceOwner = isDeviceOwner,
        refreshTrigger = refreshTrigger,
        modifier = Modifier.testTag("dns_lock_card"),
      )

      // Safe Mode Block Card
      SafeBootLockCard(
        isDeviceOwner = isDeviceOwner,
        modifier = Modifier.testTag("safe_boot_lock_card"),
      )
      ChromeDnsLockCard(isDeviceOwner = isDeviceOwner)


      // Sideload & VPN Config Block Card
      SideloadBlockCard(
        isDeviceOwner = isDeviceOwner,
        modifier = Modifier.testTag("sideload_block_card"),
      )

      // VPN Slot Lock Card
      VpnSlotLockCard(
        isDeviceOwner = isDeviceOwner,
        modifier = Modifier.testTag("vpn_slot_lock_card"),
      )

      // Emergency Internet Restore Button (Hidden if VPN is working)
      if (isDeviceOwner) {
        Button(
          onClick = { VpnSetupManager.disableAlwaysOnVpn(context) },
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
          modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
          Text("Emergency: Restore Internet (Disable VPN)")
        }
      }

      // Self-Protection Card
      SelfProtectCard(
        isDeviceOwner = isDeviceOwner,
        refreshTrigger = refreshTrigger,
        modifier = Modifier.testTag("self_protect_card"),
      )

      // Refresh Button
      Button(
        onClick = { 
          Log.e("MainActivity", "Manual scan button clicked.")
          val pm = context.packageManager
          val installedPackages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
          } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
          }
          installedPackages.forEach { packageInfo ->
            val pkgName = packageInfo.packageName
            
            // Observe-only quarantine logging
            NetworkQuarantineManager.logQuarantineDecision(packageInfo)

            if (AppBlocklistManager.isPackageBlocked(context, pkgName)) {
              Log.e("MainActivity", "Manual scan: Found $pkgName")
              AppBlocklistManager.blockPackageIfNeeded(context, pkgName)
            }
          }
          refreshStatus() 
        },
        modifier =
          Modifier
            .fillMaxWidth()
            .height(52.dp)
            .testTag("refresh_status_button"),
      ) {
        Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = "Scan & Refresh Status", fontSize = 16.sp)
      }
    }
  }
}

@Composable
fun StatusCard(
  isDeviceOwner: Boolean,
  isAdminActive: Boolean,
  modifier: Modifier = Modifier,
) {
  val isActive = isDeviceOwner
  val statusText = if (isActive) "Active" else "Not Active"
  val primaryColor =
    if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
  val containerColor =
    if (isActive) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.errorContainer
  val onContainerColor =
    if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onErrorContainer

  Card(
    modifier = modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = containerColor),
    shape = RoundedCornerShape(20.dp),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Box(
        modifier =
          Modifier
            .size(68.dp)
            .clip(CircleShape)
            .background(primaryColor.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = if (isActive) Icons.Default.CheckCircle else Icons.Default.Warning,
          contentDescription = null,
          modifier = Modifier.size(38.dp),
          tint = primaryColor,
        )
      }

      Spacer(modifier = Modifier.height(14.dp))

      Text(
        text = "Status: $statusText",
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        color = onContainerColor,
      )

      Spacer(modifier = Modifier.height(6.dp))

      Text(
        text =
          if (isActive) {
            "Device Owner permissions are active"
          } else if (isAdminActive) {
            "Device Admin active (Device Owner not set)"
          } else {
            "App is not configured as Device Owner"
          },
        fontSize = 14.sp,
        color = onContainerColor.copy(alpha = 0.85f),
      )
    }
  }
}

@Composable
fun DeviceOwnerInfoCard(isDeviceOwner: Boolean, modifier: Modifier = Modifier) {
  Card(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
      ),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Default.Info,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "Device Owner Status",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Device Owner:",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = if (isDeviceOwner) "Active" else "Not Active",
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.Bold,
          color =
            if (isDeviceOwner) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
        )
      }

      Text(
        text = "Checked via: DevicePolicyManager.isDeviceOwnerApp(packageName)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        fontFamily = FontFamily.Monospace,
      )
    }
  }
}

@Composable
fun ProvisioningInstructionCard(packageName: String, modifier: Modifier = Modifier) {
  Card(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
      ),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        text = "ADB Provisioning Command",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )

      Text(
        text = "To provision Lock-In as the Device Owner via ADB:",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = "adb shell dpm set-device-owner $packageName/.LockInAdminReceiver",
          fontFamily = FontFamily.Monospace,
          fontSize = 12.sp,
          modifier = Modifier.padding(12.dp),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Text(
        text = "Note: Ensure no other accounts (Google, etc.) exist on the device before running this command.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
      )
    }
  }
}

@Composable
fun DnsLockCard(
  isDeviceOwner: Boolean,
  refreshTrigger: Int = 0,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var lockResult by remember { mutableStateOf<Boolean?>(null) }

  // Check if lock is active (either just applied in this session or configured on device)
  val isDnsLockAppliedOnDevice = remember(refreshTrigger, lockResult) {
    checkIsDnsLockApplied(context)
  }
  val isDnsLockActive = isDeviceOwner && (lockResult == true || isDnsLockAppliedOnDevice)

  // Cooldown states
  var remainingMillis by remember(refreshTrigger, isDnsLockActive) {
    mutableLongStateOf(CooldownManager.getRemainingCooldownMillis(context, "dns"))
  }
  var isReady by remember(refreshTrigger, isDnsLockActive) {
    mutableStateOf(CooldownManager.isChangeReady(context, "dns"))
  }

  val containerColor =
    if (isDeviceOwner) MaterialTheme.colorScheme.surfaceContainerHigh
    else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f)

  Card(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = containerColor),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Default.Security,
          contentDescription = null,
          tint =
            if (isDeviceOwner) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline,
          modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "DNS Lock",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color =
            if (isDeviceOwner) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.outline,
        )
      }

      if (!isDeviceOwner) {
        Text(
          text = "Requires Device Owner",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
          fontWeight = FontWeight.Medium,
        )
      } else {
        Text(
          text = "Enforces private DNS provider (NextDNS) and disallows user changes.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      if (!isDnsLockActive) {
        Button(
          onClick = {
            lockResult = applyDnsLock(context)
            remainingMillis = CooldownManager.getRemainingCooldownMillis(context, "dns")
            isReady = CooldownManager.isChangeReady(context, "dns")
          },
          enabled = isDeviceOwner,
          modifier =
            Modifier
              .fillMaxWidth()
              .height(48.dp)
              .testTag("apply_dns_lock_button"),
        ) {
          Text(text = "Apply DNS Lock")
        }

        if (lockResult != null) {
          val isSuccess = lockResult == true
          Text(
            text = if (isSuccess) "DNS Lock Applied" else "Failed — check Device Owner status",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color =
              if (isSuccess) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("dns_lock_result_text"),
          )
        }
      }

      // Secondary section: 7-day cooldown "Request Change" mechanism
      if (isDnsLockActive) {
        HorizontalDivider(
          modifier = Modifier.padding(vertical = 4.dp),
          color = MaterialTheme.colorScheme.outlineVariant,
        )

        Text(
          text = "DNS Change Cooldown",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
        )

        if (isReady) {
          Text(
            text = "7-day cooldown elapsed. You may now confirm and reapply your DNS lock.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
          )
          Button(
            onClick = {
              val success = applyDnsLock(context)
              lockResult = success
              if (success) {
                CooldownManager.clearRequest(context, "dns")
              }
              remainingMillis = CooldownManager.getRemainingCooldownMillis(context, "dns")
              isReady = CooldownManager.isChangeReady(context, "dns")
            },
            modifier =
              Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("confirm_reapply_dns_lock_button"),
          ) {
            Text(text = "Confirm & Reapply DNS Lock")
          }
        } else if (remainingMillis > 0L) {
          val totalHours = remainingMillis / (1000 * 60 * 60)
          val days = totalHours / 24
          val hours = totalHours % 24

          Text(
            text = "Change Request Pending",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = "Days: $days, Hours: $hours",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("dns_cooldown_remaining_text"),
          )
        } else {
          Button(
            onClick = {
              CooldownManager.requestChange(context, "dns")
              remainingMillis = CooldownManager.getRemainingCooldownMillis(context, "dns")
              isReady = CooldownManager.isChangeReady(context, "dns")
            },
            modifier =
              Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("request_dns_change_button"),
          ) {
            Text(text = "Request DNS Change")
          }
        }
      }
    }
  }
}

@Composable
fun SafeBootLockCard(isDeviceOwner: Boolean, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  var lockResult by remember { mutableStateOf<Boolean?>(null) }

  val containerColor =
    if (isDeviceOwner) MaterialTheme.colorScheme.surfaceContainerHigh
    else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f)

  Card(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = containerColor),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Default.Security,
          contentDescription = null,
          tint =
            if (isDeviceOwner) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline,
          modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "Safe Mode Block",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color =
            if (isDeviceOwner) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.outline,
        )
      }

      if (!isDeviceOwner) {
        Text(
          text = "Requires Device Owner",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
          fontWeight = FontWeight.Medium,
        )
      } else {
        Text(
          text = "Disallows booting the device into Safe Mode to bypass restrictions.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Button(
        onClick = {
          lockResult = applySafeBootLock(context)
        },
        enabled = isDeviceOwner,
        modifier =
          Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("apply_safe_boot_lock_button"),
      ) {
        Text(text = "Apply Safe Mode Block")
      }

      if (lockResult != null) {
        val isSuccess = lockResult == true
        Text(
          text = if (isSuccess) "Safe Mode Block Applied" else "Failed — check Device Owner status",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Bold,
          color =
            if (isSuccess) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
          modifier = Modifier.testTag("safe_boot_lock_result_text"),
        )
      }
    }
  }
}

@Composable
fun SideloadBlockCard(isDeviceOwner: Boolean, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  var lockResult by remember { mutableStateOf<Boolean?>(null) }

  val containerColor =
    if (isDeviceOwner) MaterialTheme.colorScheme.surfaceContainerHigh
    else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f)

  Card(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = containerColor),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Default.Security,
          contentDescription = null,
          tint =
            if (isDeviceOwner) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline,
          modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "Sideload & VPN Config Block",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color =
            if (isDeviceOwner) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.outline,
        )
      }

      if (!isDeviceOwner) {
        Text(
          text = "Requires Device Owner",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
          fontWeight = FontWeight.Medium,
        )
      } else {
        Text(
          text = "Disallows installing apps from unknown sources (sideloading) and prevents configuring VPNs.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Text(
        text = "This cannot be undone once applied.",
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag("sideload_block_warning_text"),
      )

      Button(
        onClick = {
          lockResult = SideloadBlockManager.applySideloadBlock(context)
        },
        enabled = isDeviceOwner,
        modifier =
          Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("apply_sideload_block_button"),
      ) {
        Text(text = "Apply Sideload & VPN Block")
      }

      if (lockResult != null) {
        val isSuccess = lockResult == true
        Text(
          text = if (isSuccess) "Sideload & VPN Block Applied" else "Failed — check Device Owner status",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Bold,
          color =
            if (isSuccess) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
          modifier = Modifier.testTag("sideload_block_result_text"),
        )
      }
    }
  }
}

@Composable
fun ChromeDnsLockCard(isDeviceOwner: Boolean, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  var result by remember { mutableStateOf<Boolean?>(null) }

  Card(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(text = "Chrome DNS Lock", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Text(
        text = "Forces Chrome's Secure DNS off so it can't bypass the system DNS lock. Verify via chrome://policy after applying.",
        style = MaterialTheme.typography.bodySmall,
      )
      Button(
        onClick = { result = applyChromeDnsLock(context) },
        enabled = isDeviceOwner,
        modifier = Modifier.fillMaxWidth().height(48.dp),
      ) {
        Text(text = "Apply Chrome DNS Lock")
      }
      if (result != null) {
        Text(text = if (result == true) "Applied — verify in chrome://policy" else "Failed")
      }
    }
  }
}

@Composable
fun SelfProtectCard(
  isDeviceOwner: Boolean,
  refreshTrigger: Int = 0,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var applyResult by remember { mutableStateOf<Boolean?>(null) }
  var removeResult by remember { mutableStateOf<Boolean?>(null) }

  // Check if self-protection is active (either just applied in this session or configured on device)
  val isSelfProtectAppliedOnDevice = remember(refreshTrigger, applyResult, removeResult) {
    checkIsSelfProtectApplied(context)
  }
  val isSelfProtectActive =
    isDeviceOwner && (applyResult == true || (removeResult != true && isSelfProtectAppliedOnDevice))

  // Cooldown states for key "self_protect"
  var remainingMillis by remember(refreshTrigger, isSelfProtectActive) {
    mutableLongStateOf(CooldownManager.getRemainingCooldownMillis(context, "self_protect"))
  }
  var isReady by remember(refreshTrigger, isSelfProtectActive) {
    mutableStateOf(CooldownManager.isChangeReady(context, "self_protect"))
  }

  val containerColor =
    if (isDeviceOwner) MaterialTheme.colorScheme.surfaceContainerHigh
    else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f)

  Card(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = containerColor),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Default.Security,
          contentDescription = null,
          tint =
            if (isDeviceOwner) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline,
          modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "Self-Protection",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color =
            if (isDeviceOwner) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.outline,
        )
      }

      if (!isDeviceOwner) {
        Text(
          text = "Requires Device Owner",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
          fontWeight = FontWeight.Medium,
        )
      } else {
        Text(
          text = "Prevents uninstalling Lock-In and blocks access to app management settings.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      if (!isSelfProtectActive) {
        Button(
          onClick = {
            removeResult = null
            val success = SelfProtectManager.applySelfProtect(context)
            applyResult = success
            remainingMillis = CooldownManager.getRemainingCooldownMillis(context, "self_protect")
            isReady = CooldownManager.isChangeReady(context, "self_protect")
          },
          enabled = isDeviceOwner,
          modifier =
            Modifier
              .fillMaxWidth()
              .height(48.dp)
              .testTag("apply_self_protect_button"),
        ) {
          Text(text = "Apply Self-Protection")
        }
      }

      // Display status for the most recent action
      if (applyResult != null) {
        val isSuccess = applyResult == true
        Text(
          text = if (isSuccess) "Self-Protection Applied" else "Failed — check Device Owner status",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Bold,
          color =
            if (isSuccess) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
          modifier = Modifier.testTag("self_protect_result_text"),
        )
      } else if (removeResult != null) {
        val isSuccess = removeResult == true
        Text(
          text = if (isSuccess) "Self-Protection Removed" else "Failed to remove",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Bold,
          color =
            if (isSuccess) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
          modifier = Modifier.testTag("self_protect_result_text"),
        )
      }

      // Secondary section: 7-day cooldown "Request Change" mechanism
      if (isSelfProtectActive) {
        HorizontalDivider(
          modifier = Modifier.padding(vertical = 4.dp),
          color = MaterialTheme.colorScheme.outlineVariant,
        )

        Text(
          text = "Self-Protection Removal Cooldown",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
        )

        if (isReady) {
          Text(
            text = "7-day cooldown elapsed. You may now confirm and remove self-protection.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
          )
          Button(
            onClick = {
              applyResult = null
              val success = SelfProtectManager.removeSelfProtect(context)
              removeResult = success
              if (success) {
                CooldownManager.clearRequest(context, "self_protect")
              }
              remainingMillis = CooldownManager.getRemainingCooldownMillis(context, "self_protect")
              isReady = CooldownManager.isChangeReady(context, "self_protect")
            },
            modifier =
              Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("confirm_remove_self_protect_button"),
          ) {
            Text(text = "Confirm & Remove Self-Protection")
          }
        } else if (remainingMillis > 0L) {
          val totalHours = remainingMillis / (1000 * 60 * 60)
          val days = totalHours / 24
          val hours = totalHours % 24

          Text(
            text = "Removal Request Pending",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = "Days: $days, Hours: $hours",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("self_protect_cooldown_remaining_text"),
          )
        } else {
          Button(
            onClick = {
              CooldownManager.requestChange(context, "self_protect")
              remainingMillis = CooldownManager.getRemainingCooldownMillis(context, "self_protect")
              isReady = CooldownManager.isChangeReady(context, "self_protect")
            },
            modifier =
              Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("request_self_protect_removal_button"),
          ) {
            Text(text = "Request Self-Protection Removal")
          }
        }
      }
    }
  }
}
@Composable
fun VpnSlotLockCard(isDeviceOwner: Boolean, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  var vpnResult by remember { mutableStateOf<Boolean?>(null) }

  val vpnConsentLauncher = rememberLauncherForActivityResult(
    contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
  ) { activityResult ->
    if (activityResult.resultCode == android.app.Activity.RESULT_OK) {
      vpnResult = VpnSetupManager.startLocalVpnDirectly(context)
    } else {
      vpnResult = false
    }
  }

  val containerColor =
    if (isDeviceOwner) MaterialTheme.colorScheme.surfaceContainerHigh
    else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f)

  Card(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = containerColor),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Default.VpnKey,
          contentDescription = null,
          tint = if (isDeviceOwner) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
          modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "VPN Slot Lock",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = if (isDeviceOwner) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
        )
      }

      if (!isDeviceOwner) {
        Text(
          text = "Requires Device Owner",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
          fontWeight = FontWeight.Medium,
        )
      } else {
        Text(
          text = "Occupies the device's VPN slot so external VPNs cannot connect. Does not intercept internet traffic.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Button(
        onClick = {
          if (isDeviceOwner) {
            // As Device Owner, we force Always-On VPN which bypasses the consent dialog
            vpnResult = VpnSetupManager.establishAlwaysOnVpn(context)
          } else {
            val prepareIntent = VpnSetupManager.getPrepareIntent(context)
            if (prepareIntent != null) {
              vpnConsentLauncher.launch(prepareIntent)
            } else {
              vpnResult = VpnSetupManager.startLocalVpnDirectly(context)
            }
          }
        },
        enabled = isDeviceOwner,
        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("start_local_vpn_button"),
      ) {
         Text(text = "Enforce Always-On VPN")
      }

      if (vpnResult != null) {
        val isStarted = vpnResult == true
        Text(
          text = if (isStarted) "VPN Slot Locked" else "Failed — consent denied or error",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Bold,
          color = if (isStarted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
          modifier = Modifier.testTag("vpn_slot_lock_result_text"),
        )
      }
    }
  }
}
fun checkIsSelfProtectApplied(context: Context): Boolean {
  val dpm =
    context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
      ?: return false
  val adminComponent = LockInAdminReceiver.getComponentName(context)
  return try {
    if (dpm.isDeviceOwnerApp(context.packageName)) {
      val isUninstallBlocked = dpm.isUninstallBlocked(adminComponent, context.packageName)
      val restrictions = dpm.getUserRestrictions(adminComponent)
      val isDisallowAppsControl = restrictions.getBoolean(UserManager.DISALLOW_APPS_CONTROL, false)
      isUninstallBlocked || isDisallowAppsControl
    } else {
      false
    }
  } catch (e: Exception) {
    false
  }
}

fun checkIsDnsLockApplied(context: Context): Boolean {
  val dpm =
    context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
      ?: return false
  val adminComponent = LockInAdminReceiver.getComponentName(context)
  return try {
    if (dpm.isDeviceOwnerApp(context.packageName)) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val restrictions = dpm.getUserRestrictions(adminComponent)
        restrictions.getBoolean(UserManager.DISALLOW_CONFIG_PRIVATE_DNS, false)
      } else {
        false
      }
    } else {
      false
    }
  } catch (e: Exception) {
    false
  }
}

fun checkIsDeviceOwner(context: Context): Boolean {
  val dpm =
    context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
      ?: return false
  return dpm.isDeviceOwnerApp(context.packageName)
}

fun checkIsAdminActive(context: Context): Boolean {
  val dpm =
    context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
      ?: return false
  val component = LockInAdminReceiver.getComponentName(context)
  return dpm.isAdminActive(component)
}

@Preview(showBackground = true)
@Composable
fun LockInScreenPreview() {
  MyApplicationTheme { LockInScreen() }
}

