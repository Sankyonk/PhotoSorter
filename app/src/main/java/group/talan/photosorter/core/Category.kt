package group.talan.photosorter.core

/** Четыре целевые папки. Имя папки — то, что увидит пользователь в галерее. */
enum class Category(val folderName: String) {
    WORK("Работа"),
    DOCUMENTS("Документы"),
    FAMILY("Семья"),
    OTHER("Другое"),
}
