// impotent c SHARP lines of code:





/*if the camera.main returens null exception do:*/


  Camera myCamera;
    public Camera m_CameraTwo;



    void Start()
    {
      

    GameObject cameraObject = GameObject.Find("Camera1"); // change for the name of the tag of the camera
        myCamera = cameraObject.GetComponent<Camera>();
        Vector2 mousePosition =
                        new Vector2(myCamera.ScreenToWorldPoint(Input.mousePosition).x,
                        myCamera.ScreenToWorldPoint(Input.mousePosition).y);

                        }

        /*and use myCamera variable whereever needed*/