package com.bhaskar.reelcounter

import android.content.Context
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable

/**
 * A tickable chip that clearly shows when it's on: a check mark and a filled background (the
 * default chip looked almost the same ticked or not, found on a OnePlus, 8 Oct).
 */
fun filterChip(ctx: Context): Chip = Chip(ctx).apply {
    setChipDrawable(ChipDrawable.createFromAttributes(ctx, null, 0, com.google.android.material.R.style.Widget_Material3_Chip_Filter))
    isCheckable = true
    isCheckedIconVisible = true
}
