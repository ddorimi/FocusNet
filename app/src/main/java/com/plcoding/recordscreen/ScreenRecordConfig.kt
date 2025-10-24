package com.plcoding.recordscreen

import android.content.Intent
import android.os.Build
import android.os.Parcel
import android.os.Parcelable

data class ScreenRecordConfig(
    val resultCode: Int,
    val data: Intent,
    val modelFileName: String = "focusnet.tflite",
    val modelType: ModelType = ModelType.FOCUSNET,
    val isVoiceAlertEnabled: Boolean = true,
    val confidenceThreshold: Float = 0.45f
) : Parcelable {

    constructor(parcel: Parcel) : this(
        resultCode = parcel.readInt(),
        data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            parcel.readParcelable(Intent::class.java.classLoader, Intent::class.java)!!
        } else {
            @Suppress("DEPRECATION")
            parcel.readParcelable(Intent::class.java.classLoader)!!
        },
        modelFileName = parcel.readString() ?: "focusnet.tflite",
        modelType = ModelType.valueOf(parcel.readString() ?: "FOCUSNET"),
        isVoiceAlertEnabled = parcel.readByte() != 0.toByte(),
        confidenceThreshold = parcel.readFloat()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(resultCode)
        parcel.writeParcelable(data, flags)
        parcel.writeString(modelFileName)
        parcel.writeString(modelType.name)
        parcel.writeByte(if (isVoiceAlertEnabled) 1 else 0)
        parcel.writeFloat(confidenceThreshold)
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

enum class ModelType {
    FOCUSNET,
    BASELINE
}