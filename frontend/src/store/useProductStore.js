import { create } from 'zustand';
import toast from 'react-hot-toast';

export const useProductStore = create((set, get) => ({
    products: [],
    loading: false,
    error: null,
    currentProduct: null,

    formData: {
        name: "",
        image: "",
        price: "",
        discount_percentage: "",
        lead_time_days: "",
        minimum_order_quantity: "",
        available_quantity: ""
    },

    setFormData: (formData) => set({ formData }),
    resetForm: () => set({
        formData: {
            name: "",
            image: "",
            price: "",
            discount_percentage: "",
            lead_time_days: "",
            minimum_order_quantity: "",
            available_quantity: ""
        }
    }),

    addProduct: async (e, api) => {
        e.preventDefault();
        set({ loading: true });
        try {
            const { formData } = get();
            console.log("Form data before submit:", formData);

            await api.post('/products', formData);
            await get().fetchProducts(api);
            get().resetForm();
            toast.success("Product added successfully.");
            document.getElementById('add_product_modal').close();
        } catch (error) {
            console.error("Error in addProduct function:", error);
            const errorMessage = error.response?.data?.message || "Something went wrong";
            toast.error(errorMessage);
        } finally {
            set({ loading: false });
        }
    },

    fetchProducts: async (api) => {
        set({ loading: true });
        try {
            const response = await api.get('/products');
            set({ products: response.data.data, error: null });
        } catch (err) {
            if (err.response?.status === 429) {
                set({ error: "Too many requests. Rate limit exceeded.", products: [] });
            } else {
                set({ error: "Something went wrong.", products: [] });
            }
        } finally {
            set({ loading: false });
        }
    },

    deleteProduct: async (id, api) => {
        set({ loading: true });
        try {
            await api.delete(`/products/${id}`);
            set(prev => ({ products: prev.products.filter(product => product.id !== id) }));
            toast.success("Product deleted successfully.");
        } catch (error) {
            console.log("Error deleting product:", error);
            toast.error("Something went wrong.");
        } finally {
            set({ loading: false });
        }
    },

    fetchProduct: async (id, api) => {
        set({ loading: true });
        try {
            const response = await api.get(`/products/${id}`);
            set({ 
                currentProduct: response.data.data,
                formData: response.data.data, // prefill form with current product data
                error: null
            });
        } catch (error) {
            console.log("Error fetching product:", error);
            set({ error: "Something went wrong.", currentProduct: null });
        } finally {
            set({ loading: false });
        }
    },

    updateProduct: async (id, api) => {
        set({ loading: true });
        try {
            const { formData } = get();
            const response = await api.put(`/products/${id}`, formData);
            set({ currentProduct: response.data.data });
            toast.success("Product updated successfully.");
        } catch (error) {
            toast.error("Something went wrong.");
            console.log("Error updating product:", error);
        } finally {
            set({ loading: false });
        }
    },
}));