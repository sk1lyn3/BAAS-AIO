#pragma once
#include <android/native_window.h>
#include <media/NdkImage.h>

// Takes ownership of the window; offer takes image ownership only when true.
void preview_window(ANativeWindow *window);
bool preview_offer(AImage *image);
void preview_stop();
