package com.example

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
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
import com.example.ui.theme.MyApplicationTheme

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

  fun refreshStatus() {
    isDeviceOwner = checkIsDeviceOwner(context)
    isAdminActive = checkIsAdminActive(context)
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

      // Refresh Button
      Button(
        onClick = { refreshStatus() },
        modifier =
          Modifier
            .fillMaxWidth()
            .height(52.dp)
            .testTag("refresh_status_button"),
      ) {
        Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = "Refresh Status", fontSize = 16.sp)
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

