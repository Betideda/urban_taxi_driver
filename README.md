# Urban Taxi Driver App

This is an Android application for taxi drivers, connecting them to a dispatch system and their taxi meter. The app is currently under development.

## Features

*   **Dispatch Integration:** Connects to a dispatch system to receive and manage ride requests.
*   **Taxi Meter Integration:** Communicates with a taxi meter device.
*   **Mapping and Navigation:** Uses Google Maps for navigation and displaying routes.
*   **Real-time Updates:** Utilizes Pusher for real-time communication with the backend.

## Tech Stack

*   **Language:** [Kotlin](https://kotlinlang.org/)
*   **UI:** [Jetpack Compose](https://developer.android.com/jetpack/compose)
*   **Architecture:** MVVM (Model-View-ViewModel) - *Assumed, based on common practice with Jetpack Compose*
*   **Networking:**
    *   [Retrofit](https://square.github.io/retrofit/) for RESTful API communication.
    *   [Gson](https://github.com/google/gson) for JSON serialization/deserialization.
*   **Asynchronous Programming:** [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html)
*   **Maps:** [Google Maps Platform](https://developers.google.com/maps)
*   **Real-time:** [Pusher](https://pusher.com/)
*   **Local SDK:** `LibDigitaxSDK-release.aar` for taxi meter integration.

## Project Structure

The project is organized into the following packages:

*   `activity`: Contains the main Android activities.
*   `fragments`: Holds the UI fragments.
*   `models`: Defines the data models for the application.
*   `networkApi`: Manages network requests and API communication.
*   `recycler`: Contains RecyclerView adapters and view holders.
*   `services`: Includes background services for tasks like location tracking.
*   `ui`: Contains Jetpack Compose UI components.
*   `utils`: Provides utility classes and functions.

## Building the Project

1.  Clone the repository.
2.  Open the project in Android Studio.
3.  The project uses the Gradle build system. Android Studio will automatically download the required dependencies.
4.  Make sure you have the `LibDigitaxSDK-release.aar` file in the `app/libs` directory.
5.  Build and run the application on an Android device or emulator.


