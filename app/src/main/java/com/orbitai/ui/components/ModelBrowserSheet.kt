package com.orbitai.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.orbitai.domain.models.DetailedModelInfo

private const val FIELD_HORIZONTAL_PADDING_DP = 16
private const val FIELD_VERTICAL_PADDING_DP = 8
private const val FIELD_SHAPE_RADIUS = 12
private const val SPACER_HEIGHT_DP = 12
private const val CONTENT_PADDING_DP = 4
private const val CARD_SHAPE_RADIUS = 12
private const val CARD_PADDING_DP = 12
private const val CARD_SPACING_DP = 6
private const val CARD_ACTIVE_ELEVATION_DP = 2
private const val ACTIVE_CONTAINER_ALPHA = 0.3f
private const val ACTIVE_BADGE_PADDING_HORIZONTAL_DP = 6
private const val ACTIVE_BADGE_PADDING_VERTICAL_DP = 2
private const val ACTIVE_BADGE_SPACER_WIDTH_DP = 8
private const val INFO_SPACING_DP = 16
private const val BADGE_SHAPE_RADIUS = 4
private const val SPACER_HEIGHT_SMALL_DP = 2
private const val SPACER_HEIGHT_MEDIUM_DP = 8

private const val TITLE_SELECT_MODEL = "Select Model"
private const val CLOSE_DESCRIPTION = "Close"
private const val SEARCH_PLACEHOLDER = "Search models..."
private const val LOADING_MESSAGE = "Fetching models from OpenRouter..."
private const val EMPTY_MESSAGE = "No models available. Make sure your API key is valid."
private const val ACTIVE_LABEL = "Active"
private const val CONTEXT_LABEL = "Context"
private const val PROMPT_LABEL = "Prompt"
private const val COMPLETION_LABEL = "Completion"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelBrowserSheet(
    models: List<DetailedModelInfo>,
    selectedModelId: String,
    isLoading: Boolean,
    onModelSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredModels = remember(models, searchQuery) {
        if (searchQuery.isBlank()) models
        else models.filter {
            it.id.contains(searchQuery, ignoreCase = true) ||
            it.name.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(TITLE_SELECT_MODEL, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = CLOSE_DESCRIPTION)
                    }
                }
            )
        }
    ) { padding ->
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(SEARCH_PLACEHOLDER) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = FIELD_HORIZONTAL_PADDING_DP.dp, vertical = FIELD_VERTICAL_PADDING_DP.dp),
                    shape = RoundedCornerShape(FIELD_SHAPE_RADIUS.dp)
                )

                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(SPACER_HEIGHT_DP.dp))
                            Text(
                                LOADING_MESSAGE,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (models.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            EMPTY_MESSAGE,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        "${filteredModels.size} models",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = FIELD_HORIZONTAL_PADDING_DP.dp, vertical = CONTENT_PADDING_DP.dp)
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(horizontal = FIELD_HORIZONTAL_PADDING_DP.dp, vertical = CONTENT_PADDING_DP.dp),
                        verticalArrangement = Arrangement.spacedBy(CARD_SPACING_DP.dp)
                    ) {
                        // Stable key = model id so Compose can reuse item compositions
                        // across filter changes instead of rebuilding every card.
                        items(filteredModels, key = { it.id }) { model ->
                            ModelCard(
                                model = model,
                                isSelected = model.id == selectedModelId,
                                onClick = {
                                    onModelSelected(model.id)
                                    onDismiss()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: DetailedModelInfo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(CARD_SHAPE_RADIUS.dp),
        color = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = ACTIVE_CONTAINER_ALPHA)
        else
            MaterialTheme.colorScheme.surface,
        tonalElevation = if (isSelected) CARD_ACTIVE_ELEVATION_DP.dp else 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CARD_SHAPE_RADIUS.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(CARD_PADDING_DP.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isSelected) {
                    Spacer(Modifier.width(ACTIVE_BADGE_SPACER_WIDTH_DP.dp))
                    Surface(
                        shape = RoundedCornerShape(BADGE_SHAPE_RADIUS.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            ACTIVE_LABEL,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(
                                horizontal = ACTIVE_BADGE_PADDING_HORIZONTAL_DP.dp,
                                vertical = ACTIVE_BADGE_PADDING_VERTICAL_DP.dp
                            )
                        )
                    }
                }
            }

            Spacer(Modifier.height(SPACER_HEIGHT_SMALL_DP.dp))
            Text(
                text = model.id,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(SPACER_HEIGHT_MEDIUM_DP.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(INFO_SPACING_DP.dp)
            ) {
                if (model.contextLength > 0) {
                    Column {
                        Text(
                            CONTEXT_LABEL,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            model.contextDisplay,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                if (model.promptPrice != "0") {
                    Column {
                        Text(
                            PROMPT_LABEL,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            model.formattedPromptPrice,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                if (model.completionPrice != "0") {
                    Column {
                        Text(
                            COMPLETION_LABEL,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            model.formattedCompletionPrice,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
