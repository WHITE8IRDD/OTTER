package com.otter.app.ui.screens.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.otter.app.data.model.DownloadItem
import com.otter.app.data.repository.DownloadRepository
import com.otter.app.util.FileHelper
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DownloadRepository(application)

    val downloads: StateFlow<List<DownloadItem>> = repository.allDownloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteItem(item: DownloadItem) {
        viewModelScope.launch {
            repository.deleteDownloadItem(item)
        }
    }

    fun deleteItemWithFile(item: DownloadItem) {
        viewModelScope.launch {
            FileHelper.deleteFile(item.filePath)
            repository.deleteDownloadItem(item)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearAllDownloads()
        }
    }

    fun openFile(context: android.content.Context, item: DownloadItem) {
        FileHelper.openFile(context, item.filePath)
    }

    fun locateFile(context: android.content.Context, item: DownloadItem) {
        FileHelper.locateFile(context, item.filePath)
    }

    fun shareFile(context: android.content.Context, item: DownloadItem) {
        FileHelper.shareFile(context, item.filePath)
    }

    fun fileExists(item: DownloadItem): Boolean {
        return FileHelper.fileExists(item.filePath)
    }
}
