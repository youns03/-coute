package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.VocabularyEntity
import com.example.ui.components.AtelierCard
import com.example.ui.components.EditorialSectionHeader
import com.example.ui.theme.ArabicSansFamily
import com.example.ui.theme.FrenchSerifFamily
import com.example.ui.theme.LocalEcouteAtelierColors

@Composable
fun VocabularyScreen(
    query: String,
    entries: List<VocabularyEntity>,
    onQueryChange: (String) -> Unit,
    onPronounce: (String) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val atelier = LocalEcouteAtelierColors.current
    var pendingDeleteVocab by remember { mutableStateOf<VocabularyEntity?>(null) }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 780.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text(stringResource(R.string.vocab_search_placeholder)) },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        focusedBorderColor = MaterialTheme.colorScheme.secondary,
                        unfocusedBorderColor = atelier.cardBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vocab_search_input")
                )
            }

            if (entries.isEmpty()) {
                item {
                    AtelierCard(
                        elevation = 1.dp,
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.large,
                                border = BorderStroke(1.dp, atelier.cardBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.img_empty_vocab),
                                    contentDescription = stringResource(R.string.vocab_empty_cd),
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Text(stringResource(R.string.vocab_empty_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.vocab_empty_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                item {
                    EditorialSectionHeader(
                        titleAr = stringResource(R.string.vocab_section_title),
                        folioTag = "Vocabulaire",
                        countBadge = "${entries.size}"
                    )
                }

                items(entries, key = { it.id }) { item ->
                    AtelierCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    Text(
                                        text = item.frenchTerm,
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            fontFamily = FrenchSerifFamily,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    IconButton(
                                        onClick = { onPronounce(item.frenchTerm) },
                                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                                            contentColor = MaterialTheme.colorScheme.secondary
                                        ),
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("vocab_pronounce_${item.id}")
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Rounded.VolumeUp,
                                            contentDescription = stringResource(R.string.vocab_pronounce_cd)
                                        )
                                    }
                                    IconButton(
                                        onClick = { pendingDeleteVocab = item },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            Icons.Rounded.DeleteOutline,
                                            contentDescription = stringResource(R.string.vocab_delete_cd),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(color = atelier.cardBorder.copy(alpha = 0.7f))

                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                                Text(
                                    text = item.arabicTranslation,
                                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = ArabicSansFamily)
                                )
                            }

                            if (item.contextSentence.isNotBlank() && item.contextSentence != item.frenchTerm) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                        Text(
                                            text = item.contextSentence,
                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FrenchSerifFamily),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                                        )
                                    }
                                }
                            }

                            Text(
                                text = stringResource(R.string.vocab_source_label, item.bookTitle),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    pendingDeleteVocab?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeleteVocab = null },
            title = { Text(stringResource(R.string.confirm_delete_vocab_title)) },
            text = { Text(stringResource(R.string.confirm_delete_vocab_message, target.frenchTerm.take(60))) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeleteVocab = null
                        onDelete(target.id)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteVocab = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}
