package com.example.mp3player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mp3player.data.model.*
import com.example.mp3player.ui.theme.*

/**
 * 音频库排序与筛选底部抽屉
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSortBottomSheet(
    currentSortField: SortField,
    currentSortDirection: SortDirection,
    currentDurationFilter: DurationFilter,
    currentSizeFilter: SizeFilter,
    onSortChange: (SortField, SortDirection) -> Unit,
    onFilterChange: (DurationFilter, SizeFilter) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = "排序与筛选",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryDark
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 排序字段
            Text(text = "排序依据", fontSize = 13.sp, color = TextSecondary)
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (field in SortField.entries) {
                    val isSelected = currentSortField == field
                    FilterChipItem(
                        text = field.displayName,
                        isSelected = isSelected,
                        onClick = { onSortChange(field, currentSortDirection) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 排序方向
            Text(text = "排序方向", fontSize = 13.sp, color = TextSecondary)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (direction in SortDirection.entries) {
                    val isSelected = currentSortDirection == direction
                    FilterChipItem(
                        text = direction.displayName,
                        isSelected = isSelected,
                        onClick = { onSortChange(currentSortField, direction) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 时长筛选
            Text(text = "音频时长区间", fontSize = 13.sp, color = TextSecondary)
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (filter in DurationFilter.entries) {
                    val isSelected = currentDurationFilter == filter
                    FilterChipItem(
                        text = filter.displayName,
                        isSelected = isSelected,
                        onClick = { onFilterChange(filter, currentSizeFilter) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 文件大小筛选
            Text(text = "文件大小区间", fontSize = 13.sp, color = TextSecondary)
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (filter in SizeFilter.entries) {
                    val isSelected = currentSizeFilter == filter
                    FilterChipItem(
                        text = filter.displayName,
                        isSelected = isSelected,
                        onClick = { onFilterChange(currentDurationFilter, filter) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("完成", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun FilterChipItem(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (isSelected) PrimaryLight else SurfaceVariantLight)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = text,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) Color.White else PrimaryDark
            )
        }
    }
}
