package app.pendelwecker.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Die wenigen Symbole der App, nach Material Icons (Apache-Lizenz 2.0), ohne Symbol-Bibliothek. */
object AppIcons {
    val Back = icon("M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")
    val Refresh = icon(
        "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 " +
            "7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 " +
            "4.22,1.78L13,11h7V4l-2.35,2.35z"
    )
    val Map = icon(
        "M20.5,3l-0.16,0.03L15,5.1 9,3 3.36,4.9c-0.21,0.07 -0.36,0.25 -0.36,0.48V20.5c0,0.28 0.22,0.5 " +
            "0.5,0.5l0.16,-0.03L9,18.9l6,2.1 5.64,-1.9c0.21,-0.07 0.36,-0.25 0.36,-0.48V3.5c0,-0.28 -0.22,-0.5 " +
            "-0.5,-0.5zM15,19l-6,-2.11V5l6,2.11V19z"
    )
    val Swap = icon("M16,17.01V10h-2v7.01h-3L15,21l4,-3.99h-3zM9,3L5,6.99h3V14h2V6.99h3L9,3z")
    val Chevron = icon("M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z")
    val Search = icon(
        "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 " +
            "5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 " +
            "5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z"
    )
    val Close = icon(
        "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z"
    )
    val Alarm = icon(
        "M22,5.72l-4.6,-3.86 -1.29,1.53 4.6,3.86L22,5.72zM7.88,3.39L6.6,1.86 2,5.71l1.29,1.53 " +
            "4.59,-3.85zM12.5,8H11v6l4.75,2.85 0.75,-1.23 -4,-2.37V8zM12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9c4.97,0 " +
            "9,-4.03 9,-9s-4.03,-9 -9,-9zM12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z"
    )
    val Warning = icon("M1,21h22L12,2 1,21zM13,18h-2v-2h2v2zM13,14h-2v-4h2v4z")
    val Info = icon(
        "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-6h2v6zM13,9h-2V7h2v2z"
    )
    val Train = icon(
        "M12,2C8,2 4,2.5 4,6v9.5C4,17.43 5.57,19 7.5,19L6,20.5v0.5h2l2,-2h4l2,2h2v-0.5L16.5,19c1.93,0 " +
            "3.5,-1.57 3.5,-3.5V6c0,-3.5 -4,-4 -8,-4zM7.5,17C6.67,17 6,16.33 6,15.5S6.67,14 7.5,14 9,14.67 9,15.5 " +
            "8.33,17 7.5,17zM11,10H6V6h5v4zM16.5,17c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5 1.5,0.67 " +
            "1.5,1.5 -0.67,1.5 -1.5,1.5zM18,10h-5V6h5v4z"
    )
    val Add = icon("M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
    val OpenInNew = icon(
        "M19,19H5V5h7V3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2v7zM14,3v2h3.59" +
            "l-9.83,9.83 1.41,1.41L19,6.41V10h2V3h-7z"
    )

    private fun icon(path: String): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}
