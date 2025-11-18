package fixtures.lwjgl;

import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkCommandBuffer;

class LWJGLVulkanTest {
    @TargetMethod
    void useVulkan() {
        VkInstance instance = new VkInstance();
        // Vulkan methods with null arguments cause ambiguity - skip for shim test
        // int result = VK.vkCreateInstance(null, null, null);
        // VK.vkDestroyInstance(instance, null); // null causes ambiguity
        
        // int deviceCount = VK.vkEnumeratePhysicalDevices(instance, null, null);
        VkPhysicalDevice physicalDevice = new VkPhysicalDevice();
        // VK.vkGetPhysicalDeviceProperties(physicalDevice, null);
        
        VkDevice device = new VkDevice();
        // VK.vkCreateDevice(physicalDevice, null, null, null);
        // VK.vkDestroyDevice(device, null); // null causes ambiguity
        
        long swapchain = 0;
        // VK.vkCreateSwapchainKHR(device, null, null, null);
        // VK.vkDestroySwapchainKHR(device, swapchain, null); // null causes ambiguity
        // VK.vkGetSwapchainImagesKHR(device, swapchain, null, null);
        
        // VK.vkCreateImageView(device, null, null, null);
        // VK.vkDestroyImageView(device, 0, null); // null causes ambiguity
        // VK.vkCreateRenderPass(device, null, null, null);
        // VK.vkDestroyRenderPass(device, 0, null); // null causes ambiguity
        // VK.vkCreateFramebuffer(device, null, null, null);
        // VK.vkDestroyFramebuffer(device, 0, null); // null causes ambiguity
        
        // VK.vkCreateCommandPool(device, null, null, null);
        // VK.vkDestroyCommandPool(device, 0, null); // null causes ambiguity
        // VK.vkAllocateCommandBuffers(device, null, null);
        // VK.vkFreeCommandBuffers(device, 0, null); // null causes ambiguity
        
        VkCommandBuffer commandBuffer = new VkCommandBuffer();
        // VK.vkBeginCommandBuffer(commandBuffer, null); // null causes ambiguity
        VK.vkEndCommandBuffer(commandBuffer);
        
        VkQueue queue = new VkQueue();
        // VK.vkQueueSubmit(queue, null, 0); // null causes ambiguity
        // VK.vkQueuePresentKHR(queue, null); // null causes ambiguity
        VK.vkDeviceWaitIdle(device);
    }
}

