package bridging;

// GANTI package di atas sesuai package project Khanza Anda.

import com.mysql.jdbc.jdbc2.optional.MysqlDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import fungsi.koneksiDB;

/**
 * Logger response time API BPJS.
 *
 * Fungsi:
 * - Mengukur response time API BPJS
 * - Membandingkan dengan batas waktu
 * - Menyimpan log ke database
 * - Menentukan NORMAL / OVER / ERROR / TIMEOUT
 *
 * Kompatibel dengan Java 15.
 */
public class BPJSResponseLogger {

    private static final String SQL_GET_LIMIT =
            "SELECT batas_ms "
            + "FROM bpjs_api_limit "
            + "WHERE api_name=?";

    private static final String SQL_INSERT =
            "INSERT INTO log_bpjs_antrean "
            + "(waktu_request, waktu_response, api_name, endpoint, "
            + "kodebooking, nomorkartu, nik, durasi_ms, batas_ms, "
            + "http_status, status, error_message, response_text) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /**
     * Mendapatkan koneksi database Khanza.
     */
    private static Connection getConnection() throws SQLException {
        return koneksiDB.condb();
    }

    /**
     * Mengambil batas waktu API dari database.
     */
    public static int getBatasMs(String apiName) {

        try {
            Connection con = getConnection();

            try (PreparedStatement ps =
                    con.prepareStatement(SQL_GET_LIMIT)) {

                ps.setString(1, apiName);

                try (ResultSet rs = ps.executeQuery()) {

                    if (rs.next()) {
                        return rs.getInt("batas_ms");
                    }
                }
            }

        } catch (Exception e) {

            System.err.println(
                    "BPJSResponseLogger - Gagal membaca batas API: "
                    + e.getMessage()
            );
        }

        /*
         * Fallback apabila master belum ditemukan.
         */
        return getDefaultBatas(apiName);
    }

    /**
     * Batas default.
     *
     * Ini hanya fallback.
     * Batas utama tetap diambil dari tabel bpjs_api_limit.
     */
    private static int getDefaultBatas(String apiName) {

        switch (apiName) {

            case "GetToken":
                return 2000;

            case "GetAntrean":
                return 10000;

            case "GetStatus":
                return 2000;

            case "GetSisa":
                return 2000;

            case "Batal":
                return 5000;

            case "PasienBaru":
                return 5000;

            case "Checkin":
                return 5000;

            default:
                return 0;
        }
    }

    /**
     * Menyimpan log response API BPJS.
     */
    public static void log(
            String apiName,
            String endpoint,
            String kodeBooking,
            String nomorKartu,
            String nik,
            Timestamp waktuRequest,
            Timestamp waktuResponse,
            long durasiMs,
            Integer httpStatus,
            String status,
            String errorMessage,
            String responseText) {

        int batasMs = getBatasMs(apiName);

        try {
            Connection con = getConnection();

            try (PreparedStatement ps =
                    con.prepareStatement(SQL_INSERT)) {

                ps.setTimestamp(1, waktuRequest);
                ps.setTimestamp(2, waktuResponse);

                ps.setString(3, apiName);
                ps.setString(4, endpoint);

                ps.setString(5, kodeBooking);
                ps.setString(6, nomorKartu);
                ps.setString(7, nik);

                ps.setLong(8, durasiMs);
                ps.setInt(9, batasMs);

                if (httpStatus == null) {
                    ps.setNull(
                            10,
                            java.sql.Types.INTEGER
                    );
                } else {
                    ps.setInt(10, httpStatus);
                }

                ps.setString(11, status);
                ps.setString(12, errorMessage);

                /*
                 * Jangan menyimpan response terlalu panjang.
                 */
                if (responseText != null
                        && responseText.length() > 50000) {

                    responseText =
                            responseText.substring(0, 50000);
                }

                ps.setString(13, responseText);

                ps.executeUpdate();
            }

        } catch (Exception e) {

            /*
             * Logging tidak boleh membuat proses BPJS gagal.
             */
            System.err.println(
                    "BPJSResponseLogger - Gagal menyimpan log: "
                    + e.getMessage()
            );
        }
    }

    /**
     * Method utama untuk menjalankan API sekaligus
     * mengukur response time.
     *
     * Callable<String> berisi pemanggilan API BPJS.
     */
    public static String execute(
            String apiName,
            String endpoint,
            String kodeBooking,
            String nomorKartu,
            String nik,
            Callable<String> apiCall) throws Exception {

        long startNano = System.nanoTime();

        Timestamp waktuRequest =
                new Timestamp(System.currentTimeMillis());

        String response = null;

        String status = "NORMAL";

        String errorMessage = null;

        Timestamp waktuResponse = null;

        try {

            /*
             * Jalankan API BPJS.
             */
            response = apiCall.call();

        } catch (TimeoutException e) {

            status = "TIMEOUT";

            errorMessage = e.getMessage();

            throw e;

        } catch (Exception e) {

            status = "ERROR";

            errorMessage = e.getMessage();

            throw e;

        } finally {

            /*
             * nanoTime digunakan khusus untuk menghitung
             * elapsed time.
             */
            long endNano = System.nanoTime();

            long durasiMs =
                    (endNano - startNano) / 1_000_000L;

            waktuResponse =
                    new Timestamp(System.currentTimeMillis());

            int batasMs =
                    getBatasMs(apiName);

            /*
             * Kalau tidak terjadi ERROR/TIMEOUT,
             * cek apakah melewati batas.
             */
            if ("NORMAL".equals(status)
                    && durasiMs > batasMs) {

                status = "OVER";
            }

            /*
             * Simpan log.
             */
            log(
                    apiName,
                    endpoint,
                    kodeBooking,
                    nomorKartu,
                    nik,
                    waktuRequest,
                    waktuResponse,
                    durasiMs,
                    null,
                    status,
                    errorMessage,
                    response
            );

            /*
             * Tampilkan ke console NetBeans.
             */
            System.out.println(
                    "[BPJS] "
                    + apiName
                    + " | "
                    + durasiMs
                    + " ms"
                    + " | batas "
                    + batasMs
                    + " ms"
                    + " | "
                    + status
            );
        }

        return response;
    }

    /**
     * Method sederhana untuk logging manual.
     */
    public static void logManual(
            String apiName,
            String endpoint,
            String kodeBooking,
            String nomorKartu,
            String nik,
            long durasiMs,
            String response) {

        int batasMs = getBatasMs(apiName);

        String status;

        if (durasiMs > batasMs) {
            status = "OVER";
        } else {
            status = "NORMAL";
        }

        Timestamp sekarang =
                new Timestamp(
                        System.currentTimeMillis()
                );

        log(
                apiName,
                endpoint,
                kodeBooking,
                nomorKartu,
                nik,
                sekarang,
                sekarang,
                durasiMs,
                null,
                status,
                null,
                response
        );
    }
}