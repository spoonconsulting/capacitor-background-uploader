import Foundation

/// Streams a multipart/form-data body to disk in 1 MB chunks.
/// The file is never loaded fully into memory, so large files are safe.
enum MultipartBuilder {
    /// Writes the body to `destination` and returns the Content-Type header value (with boundary).
    /// Form fields are written in the order given; the file part is always written last,
    /// which S3 POST policies require.
    static func build(
        fields: [String: String],
        fileField: String,
        fileName: String,
        mimeType: String,
        source: URL,
        destination: URL
    ) throws -> String {
        let boundary = "BGUBoundary-\(UUID().uuidString)"
        try? FileManager.default.removeItem(at: destination)
        FileManager.default.createFile(atPath: destination.path, contents: nil)

        let out = try FileHandle(forWritingTo: destination)
        defer { try? out.close() }

        func write(_ text: String) throws {
            try out.write(contentsOf: Data(text.utf8))
        }

        for (name, value) in fields {
            try write("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(escape(name))\"\r\n\r\n\(value)\r\n")
        }

        try write("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(escape(fileField))\"; filename=\"\(escape(fileName))\"\r\nContent-Type: \(mimeType)\r\n\r\n")

        let input = try FileHandle(forReadingFrom: source)
        defer { try? input.close() }
        while true {
            let chunk = try input.read(upToCount: 1 << 20) ?? Data()
            if chunk.isEmpty { break }
            try out.write(contentsOf: chunk)
        }

        try write("\r\n--\(boundary)--\r\n")
        return "multipart/form-data; boundary=\(boundary)"
    }

    private static func escape(_ value: String) -> String {
        value.replacingOccurrences(of: "\"", with: "%22").replacingOccurrences(of: "\r\n", with: " ")
    }
}
