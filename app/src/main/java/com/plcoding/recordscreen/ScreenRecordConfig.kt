package com.plcoding.recordscreen

import android.content.Intent
import android.os.Parcel
import android.os.Parcelable

/**
 * Simple Parcelable holder for MediaProjection config.
 * It passes the permission result code and data Intent
 * from MainActivity to ScreenRecordService.
 */
data class ScreenRecordConfig(
    val resultCode: Int,
    val data: Intent
) : Parcelable {

    constructor(parcel: Parcel) : this(
        parcel.readInt(),
        parcel.readParcelable(Intent::class.java.classLoader)!!
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(resultCode)
        parcel.writeParcelable(data, flags)
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