package com.github.od276746.animetracker.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "animes")
data class Anime(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val title: String,
    val status: Status,
    val currentEpisode: Int = 0,
    val totalEpisode: Int? = null,
    val coverUrl: String? = null
)
