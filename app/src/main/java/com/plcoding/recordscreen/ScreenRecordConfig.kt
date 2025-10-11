package com.plcoding.recordscreen

import android.content.Intent
import android.os.Build
import android.os.Parcel
import android.os.Parcelable

/**
 * Simple Parcelable holder for MediaProjection config.
 * It passes the permission result code and data Intent
 * from MainActivity to ScreenRecordService.
 */
data class ScreenRecordConfig(
    val resultCode: Int,
    val data: Intent,
    val modelFileName: String = "best_integer_quant.tflite",
    val isVoiceAlertEnabled: Boolean = true
) : Parcelable {

    constructor(parcel: Parcel) : this(
        parcel.readInt(),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            parcel.readParcelable(Intent::class.java.classLoader, Intent::class.java)!!
        } else {
            @Suppress("DEPRECATION")
            parcel.readParcelable(Intent::class.java.classLoader)!!
        },
        parcel.readString() ?: "best_integer_quant.tflite",
        parcel.readByte() != 0.toByte()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(resultCode)
        parcel.writeParcelable(data, flags)
        parcel.writeString(modelFileName)
        parcel.writeByte(if (isVoiceAlertEnabled) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ScreenRecordConfig> {
        override fun createFromParcel(parcel: Parcel): ScreenRecordConfig {
            return ScreenRecordConfig(parcel)
        }

        override fun newArray(size: Int): Array<ScreenRecordConfig?> {
            return arrayOfNulls(size)
        }
    }
}