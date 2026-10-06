package dev.dedworkshop.twitchcraft.util;

import dev.dedworkshop.twitchcraft.TwitchCraftClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Безопасная запись файлов настроек: сначала во временный файл рядом, потом атомарная замена.
 * Если игра вылетит (или пропадёт питание) посреди записи — старый файл останется целым,
 * а не превратится в пустой/обрезанный JSON.
 */
public final class SafeFiles {
	private SafeFiles() {
	}

	/** Записывает текст в файл атомарно (через временный файл + переименование). */
	public static void writeAtomic(Path path, String content) throws IOException {
		writeAtomic(path, content, false);
	}

	/**
	 * То же, но для секретов (токены, ключи): на Linux/macOS файл получает права {@code rw-------},
	 * чтобы другие пользователи машины не могли его прочитать. На Windows права не трогаем.
	 */
	public static void writeAtomic(Path path, String content, boolean privateFile) throws IOException {
		Path parent = path.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
		Files.writeString(tmp, content, StandardCharsets.UTF_8);
		if (privateFile) {
			try {
				if (tmp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
					Files.setPosixFilePermissions(tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
				}
			} catch (Exception ignored) {
				// не критично: файл всё равно запишем
			}
		}
		try {
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/**
	 * Откладывает копию повреждённого файла (например, twitchcraft.json.broken-2026-10-05_18-30-00),
	 * чтобы ручные правки пользователя не пропали, когда мод сохранит настройки по умолчанию.
	 *
	 * @return путь к копии или null, если скопировать не удалось
	 */
	/**
	 * Копия файла рядом с ним с заданным суффиксом (например, «.bak-v5») — перед обновлением формата.
	 *
	 * @return путь к копии или null, если файла нет или скопировать не удалось
	 */
	public static Path backupCopy(Path path, String suffix) {
		try {
			if (path == null || !Files.exists(path)) {
				return null;
			}
			Path backup = path.resolveSibling(path.getFileName() + suffix);
			Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
			return backup;
		} catch (IOException e) {
			TwitchCraftClient.LOGGER.warn("Не удалось сохранить копию файла {}: {}", path, e.toString());
			return null;
		}
	}

	public static Path backupBroken(Path path) {
		try {
			if (!Files.exists(path)) {
				return null;
			}
			String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
			Path backup = path.resolveSibling(path.getFileName() + ".broken-" + stamp);
			Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
			return backup;
		} catch (IOException e) {
			TwitchCraftClient.LOGGER.warn("Не удалось сохранить копию повреждённого файла {}: {}", path, e.toString());
			return null;
		}
	}
}
