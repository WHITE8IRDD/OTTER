package com.otter.app.ui.screens.cookies

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.otter.app.R
import androidx.lifecycle.viewmodel.compose.viewModel
import com.otter.app.ui.components.BrutalButton
import com.otter.app.ui.components.BrutalCard
import com.otter.app.ui.components.BrutalMultilineTextField

@Composable
fun CookiesScreen(
    viewModel: CookiesViewModel = viewModel(),
    onBack: () -> Unit
) {
    val cookieText by viewModel.cookieText.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val hasCookies by viewModel.hasCookies.collectAsStateWithLifecycle()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importFromFile(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Text(
            text = stringResource(R.string.cookies_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
        )

        BrutalCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = MaterialTheme.colorScheme.primary
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.cookies_instructions_header),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.cookies_step1),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Text(
                    text = stringResource(R.string.cookies_step2),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Text(
                    text = stringResource(R.string.cookies_step3),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Text(
                    text = stringResource(R.string.cookies_step4),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        BrutalMultilineTextField(
            value = cookieText,
            onValueChange = viewModel::onCookieTextChanged,
            placeholder = stringResource(R.string.cookies_placeholder),
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            minLines = 10
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (status != null) {
            BrutalCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = if (viewModel.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    text = status!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (viewModel.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        BrutalButton(
            text = stringResource(R.string.btn_import_file),
            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
            backgroundColor = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BrutalButton(
                text = stringResource(R.string.btn_save),
                onClick = viewModel::save,
                backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.weight(1f)
            )
            if (hasCookies) {
                BrutalButton(
                    text = stringResource(R.string.btn_clear),
                    onClick = viewModel::clear,
                    backgroundColor = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
            }
            BrutalButton(
                text = stringResource(R.string.btn_back),
                onClick = onBack,
                backgroundColor = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}
